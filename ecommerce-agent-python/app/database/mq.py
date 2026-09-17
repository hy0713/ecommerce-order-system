"""RabbitMQ 管理：pika 同步客户端 + 后台守护线程（1 生产者 + 2 消费者）

架构要点：
- async 侧只向有界 queue.Queue 投递，线程内独占 BlockingConnection 消费发送
- 消费者手动 ack；领域错误 nack(requeue=False)，网络瞬时错误 nack(requeue=True)
- 消费者 DB 写入统一 run_coroutine_threadsafe 送回主事件循环（复用 async engine，
  规避 aiomysql 连接跨事件循环绑定的问题）
- MQManager.healthy 标志供上层优雅降级
"""
import asyncio
import json
import logging
import queue
import threading
import time
from typing import Awaitable, Callable, Optional

import pika

logger = logging.getLogger("app.mq")

# 交换机 / 队列 / 路由键（agent 域，遵循电商 <域>.exchange 命名约定）
DOC_EXCHANGE = "agent.doc.exchange"
DOC_QUEUE = "agent.doc.vectorize.queue"
DOC_ROUTING_KEY = "agent.doc.vectorize"

LOG_EXCHANGE = "agent.log.exchange"
LOG_QUEUE = "agent.log.save.queue"
LOG_ROUTING_KEY = "agent.log.save"

RETRY_BASE = 1.0
RETRY_MAX = 30.0


class MQManager:
    def __init__(self):
        self._params = None
        self.healthy = False  # 连接可用标志（上层降级依据）
        self._producer_thread: Optional[threading.Thread] = None
        self._consumer_threads: list[threading.Thread] = []
        self._stop_event = threading.Event()
        self._send_queue: "queue.Queue[dict]" = queue.Queue(maxsize=2000)
        self._main_loop: Optional[asyncio.AbstractEventLoop] = None
        # 消费者业务回调（在主事件循环中执行）
        self._handlers: dict[str, Callable[[dict], Awaitable[None]]] = {}

    # ---------- 生命周期 ----------
    def start(self, main_loop: asyncio.AbstractEventLoop) -> None:
        from app.config import get_settings
        s = get_settings()
        creds = pika.PlainCredentials(s.rabbitmq_user, s.rabbitmq_password)
        self._params = pika.ConnectionParameters(
            host=s.rabbitmq_host, port=s.rabbitmq_port, credentials=creds,
            heartbeat=60, connection_attempts=3, retry_delay=2,
        )
        self._main_loop = main_loop
        self._stop_event.clear()
        self._producer_thread = threading.Thread(target=self._producer_loop, name="mq-producer", daemon=True)
        self._consumer_threads = [
            threading.Thread(target=self._consumer_loop, args=(DOC_QUEUE,), name="mq-consumer-doc", daemon=True),
            threading.Thread(target=self._consumer_loop, args=(LOG_QUEUE,), name="mq-consumer-log", daemon=True),
        ]
        self._producer_thread.start()
        for t in self._consumer_threads:
            t.start()
        logger.info("MQ 已启动（producer + 2 consumers）")

    def stop(self) -> None:
        self._stop_event.set()
        logger.info("MQ 停止中...")

    # ---------- 对外：异步发送 ----------
    def publish(self, exchange: str, routing_key: str, payload: dict) -> bool:
        """异步侧投递；返回是否入队成功（False 表示队列满，上层降级）"""
        if not self.healthy:
            return False
        try:
            self._send_queue.put_nowait({"exchange": exchange, "routing_key": routing_key, "payload": payload})
            return True
        except queue.Full:
            logger.warning("MQ 发送队列已满，丢弃消息")
            return False

    # ---------- 消费者回调注册 ----------
    def register_handler(self, queue_name: str, handler: Callable[[dict], Awaitable[None]]) -> None:
        self._handlers[queue_name] = handler

    # ---------- 生产者线程 ----------
    def _producer_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                conn = pika.BlockingConnection(self._params)
                ch = conn.channel()
                self._declare(ch)
                self.healthy = True
                logger.info("MQ producer 已连接")
                while not self._stop_event.is_set():
                    try:
                        item = self._send_queue.get(timeout=1)
                    except queue.Empty:
                        continue        # 空闲超时是正常状态，不是连接故障
                    if item is None:
                        break
                    ch.basic_publish(
                        exchange=item["exchange"], routing_key=item["routing_key"],
                        body=json.dumps(item["payload"], ensure_ascii=False).encode("utf-8"),
                        properties=pika.BasicProperties(delivery_mode=2),  # 持久化
                    )
                conn.close()
            except Exception as e:
                self.healthy = False
                logger.warning("MQ producer 连接异常: %s，%s 后重连", e, RETRY_BASE)
                self._stop_event.wait(RETRY_BASE)

    # ---------- 消费者线程 ----------
    def _consumer_loop(self, queue_name: str) -> None:
        backoff = RETRY_BASE
        while not self._stop_event.is_set():
            try:
                conn = pika.BlockingConnection(self._params)
                ch = conn.channel()
                self._declare(ch)
                ch.basic_qos(prefetch_count=2)
                ch.basic_consume(queue_name, self._make_callback(queue_name, ch))
                self.healthy = True
                logger.info("MQ consumer[%s] 已连接", queue_name)
                backoff = RETRY_BASE
                while not self._stop_event.is_set():
                    conn.process_data_events(time_limit=1)
                conn.close()
            except Exception as e:
                self.healthy = False
                logger.warning("MQ consumer[%s] 连接异常: %s，%s 后重连", queue_name, e, backoff)
                self._stop_event.wait(backoff)
                backoff = min(backoff * 2, RETRY_MAX)

    def _declare(self, ch) -> None:
        ch.exchange_declare(DOC_EXCHANGE, exchange_type="topic", durable=True)
        ch.exchange_declare(LOG_EXCHANGE, exchange_type="topic", durable=True)
        ch.queue_declare(DOC_QUEUE, durable=True)
        ch.queue_declare(LOG_QUEUE, durable=True)
        ch.queue_bind(DOC_QUEUE, DOC_EXCHANGE, routing_key=DOC_ROUTING_KEY)
        ch.queue_bind(LOG_QUEUE, LOG_EXCHANGE, routing_key=LOG_ROUTING_KEY)

    def _make_callback(self, queue_name: str, ch):
        def callback(channel, method, properties, body):
            if self._stop_event.is_set():
                channel.basic_nack(method.delivery_tag, requeue=True)
                return
            try:
                payload = json.loads(body.decode("utf-8"))
            except Exception as e:
                logger.warning("MQ 消息解析失败: %s", e)
                channel.basic_nack(method.delivery_tag, requeue=False)
                return
            handler = self._handlers.get(queue_name)
            if handler is None:
                channel.basic_ack(method.delivery_tag)
                return
            # DB 操作送回主事件循环执行
            try:
                fut = asyncio.run_coroutine_threadsafe(handler(payload), self._main_loop)
                fut.result(timeout=60)
                channel.basic_ack(method.delivery_tag)
            except asyncio.TimeoutError:
                logger.error("MQ handler[%s] 处理超时，requeue", queue_name)
                channel.basic_nack(method.delivery_tag, requeue=True)
            except Exception as e:
                logger.error("MQ handler[%s] 处理异常: %s", queue_name, e)
                channel.basic_nack(method.delivery_tag, requeue=False)

        return callback


mq_manager = MQManager()

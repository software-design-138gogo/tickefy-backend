import logging


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if not hasattr(record, "request_id"):
            record.request_id = "-"
        return True


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s requestId=%(request_id)s %(message)s",
    )

    request_id_filter = RequestIdFilter()
    root_logger = logging.getLogger()
    root_logger.addFilter(request_id_filter)

    for handler in root_logger.handlers:
        handler.addFilter(request_id_filter)

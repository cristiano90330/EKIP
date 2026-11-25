"""按页对扫描 PDF 执行有并发与时间约束的 OCR，返回识别文本。"""

import io
import os
import subprocess
from threading import BoundedSemaphore
from time import monotonic

from ai.app.parser.errors import PdfOcrUnavailable


OCR_MAX_SCAN_PAGES = 30
OCR_DOCUMENT_BUDGET_S = 90
_OCR_SLOTS = BoundedSemaphore(2)
_MAX_RENDER_SIDE = 3200


def extract_scan_text(page, deadline: float) -> str:
    """在剩余截止时间和本地并发配额内识别扫描页，返回文字供 pdf_parser 构建段落。

    上游为 `ai.app.parser.pdf_parser.extract_blocks`，下游调用 `ai.app.parser.errors.PdfOcrUnavailable`。
    """
    if not _OCR_SLOTS.acquire(timeout=max(0, deadline - monotonic())):
        raise PdfOcrUnavailable("扫描页 OCR 当前繁忙，请稍后重试")
    try:
        longest_side = max(page.width, page.height, 1)
        resolution = min(220, int(72 * _MAX_RENDER_SIDE / longest_side))
        if resolution < 20:
            raise PdfOcrUnavailable("扫描页尺寸超出 OCR 支持范围")
        with io.BytesIO() as png:
            image = page.to_image(resolution=resolution).original
            try:
                image.save(png, format="PNG")
            finally:
                image.close()
            remaining = deadline - monotonic()
            if remaining <= 0:
                raise PdfOcrUnavailable("扫描页 OCR 超时，请分拆 PDF 后重试")
            result = subprocess.run(
                ["tesseract", "stdin", "stdout", "-l", "chi_sim+eng"],
                input=png.getvalue(), capture_output=True, check=True,
                timeout=min(20, remaining), env={**os.environ, "OMP_THREAD_LIMIT": "1"},
            )
        return result.stdout.decode("utf-8").strip()
    except subprocess.TimeoutExpired as exc:
        raise PdfOcrUnavailable("扫描页 OCR 超时，请分拆 PDF 后重试") from exc
    except (OSError, subprocess.CalledProcessError) as exc:
        raise PdfOcrUnavailable("扫描页 OCR 暂不可用，请检查 Tesseract 和中英语言包") from exc
    finally:
        _OCR_SLOTS.release()

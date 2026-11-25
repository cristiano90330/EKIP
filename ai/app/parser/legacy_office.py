"""识别旧版 Office 文件并使用可用的 LibreOffice 转换为新版格式。"""

import os
import shutil
import subprocess
import tempfile
from typing import Optional, Tuple

from ai.app.parser.errors import DocumentInputError

# OLE2 复合文档头（Word 97-2003 / PowerPoint 97-2003 共用）。
_OLE2_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

_CONVERT_TARGET = {"doc": "docx", "ppt": "pptx"}

_UNSUPPORTED_HINT = ("旧版二进制 .{ext} 解析需要 LibreOffice 转换，当前部署未提供 "
                     "（设置 SOFFICE_BIN 或安装 libreoffice）；请另存为 .{target} 后重新上传")


def is_legacy_binary(path: str) -> bool:
    """通过文件头识别旧版 Office 容器，供格式分派判断是否先转换。

    上游为 `ai.app.parser.pipeline._parse_by_ext`。
    """
    with open(path, "rb") as handle:
        return handle.read(8) == _OLE2_MAGIC


def soffice_binary() -> Optional[str]:
    """定位能够用于转换的 LibreOffice 可执行文件，未找到时返回空值。

    上游为 `convert_legacy`。
    """
    configured = (os.getenv("SOFFICE_BIN") or "").strip()
    if configured and os.path.exists(configured):
        return configured
    for name in ("soffice", "libreoffice"):
        found = shutil.which(name)
        if found:
            return found
    return None


# 转换指定路径的旧版 Office 文件，向调用方返回临时产物路径及其清理目录。
# 上游为 `ai.app.parser.pipeline._parse_by_ext`，下游调用 `soffice_binary`。
def convert_legacy(path: str, extension: str) -> Tuple[str, str]:
    """把 `.doc` / `.ppt` 转成 OOXML，返回 (转换后路径, 需要清理的临时目录)。

    转换失败按输入错误处理并带上原因前缀，便于在任务错误里直接看出是转换环节的问题。
    """
    target = _CONVERT_TARGET.get(extension)
    if target is None:
        raise DocumentInputError(f"不支持的文档类型: {extension}")
    binary = soffice_binary()
    if binary is None:
        raise DocumentInputError(_UNSUPPORTED_HINT.format(ext=extension, target=target))

    workdir = tempfile.mkdtemp(prefix="ekip-office-")
    try:
        completed = subprocess.run(  # noqa: S603 — 参数为固定列表，无 shell
            [binary, "--headless", "--convert-to", target, "--outdir", workdir, path],
            capture_output=True, timeout=180, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise DocumentInputError(
            f"旧版 .{extension} 转换失败（LibreOffice 调用异常）：{exc}") from exc

    converted = os.path.join(workdir, os.path.splitext(os.path.basename(path))[0] + "." + target)
    if completed.returncode != 0 or not os.path.exists(converted):
        shutil.rmtree(workdir, ignore_errors=True)
        detail = (completed.stderr or b"").decode("utf-8", errors="replace").strip()[:200]
        raise DocumentInputError(
            f"旧版 .{extension} 转换失败：{detail or 'LibreOffice 未产出目标文件'}")
    return converted, workdir

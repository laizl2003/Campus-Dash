"""冒烟：所有模块都能导入，防止语法错误只在运行时才暴露。

曾经踩过：store_mysql 里一行被拼成超长单行，导入直接 SyntaxError，
而服务端因为"连不上 MySQL 就降级内存"把错误吞了，直到发现历史没落库才查出来。
"""

from __future__ import annotations

import importlib
import pkgutil

import app


def test_every_module_imports():
    names = [mod.name for mod in pkgutil.walk_packages(app.__path__, "app.")]
    assert names, "没有发现任何模块"
    broken = []
    for name in names:
        try:
            importlib.import_module(name)
        except Exception as exc:  # noqa: BLE001
            broken.append(f"{name}: {exc}")
    assert not broken, "以下模块导入失败:\n" + "\n".join(broken)

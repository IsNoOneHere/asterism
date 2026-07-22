"""允许镜像直接从源码模块启动，不为普通代码变更重复构建 Python 包。"""

from asterism_worker.cli.main import app


if __name__ == "__main__":
    app()

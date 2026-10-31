# 使用国内可用镜像源，避免 Docker Hub DNS 解析失败
FROM docker.m.daocloud.io/library/python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Shanghai

WORKDIR /app

COPY pyproject.toml ./
RUN pip install --no-cache-dir --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple \
 && pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
      fastapi "uvicorn[standard]" pydantic "langgraph>=0.2" pymysql

COPY app ./app

# 构建期清掉宿主带进来的 __pycache__。
# 旧 .pyc 的 mtime 若比 .py 新，Python 会直接加载字节码，
# 表现为"文件改了但运行的是旧逻辑"，排查起来非常费时间。
RUN find /app -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true

EXPOSE 8000

# 双保险：运行期也不写 .pyc
CMD ["python", "-B", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

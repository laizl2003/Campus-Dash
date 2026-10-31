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

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

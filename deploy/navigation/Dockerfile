FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1-mesa-glx libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY server-python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server-python/ .

# 模型文件通过 volume 或 MinIO 下载挂载
ENV NAV_MODEL_PATH=/models/model.onnx

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

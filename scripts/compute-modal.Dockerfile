# Build from the backend repository root:
# docker build -f scripts/compute-modal.Dockerfile -t uvel-compute-modal:<code-sha> .
# TEST supplies --gpus device=0, a 7.5 GiB host-memory limit, scratch TMPDIR,
# read-only model mounts, and a serial executor/validator GPU admission permit.
FROM python:3.11-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    COMPUTE_DEVICE=cuda \
    CUBLAS_WORKSPACE_CONFIG=:4096:8 \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2

RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 libstdc++6 && rm -rf /var/lib/apt/lists/*
RUN python -m pip install --no-cache-dir torch==2.8.0 --index-url https://download.pytorch.org/whl/cu126
RUN python -m pip install --no-cache-dir \
    transformers==4.57.1 peft==0.17.1 diffusers==0.35.2 accelerate==1.11.0 \
    safetensors==0.6.2 numpy==2.2.6 Pillow==11.3.0 imageio-ffmpeg==0.6.0 huggingface-hub==0.36.0 \
    onnxruntime==1.23.2

ENV IMAGEIO_FFMPEG_NO_PREVENT_SIGINT=1

WORKDIR /app
COPY scripts/compute_v2_image_video.py scripts/compute_v2_image_video_models.json \
     scripts/compute_v2_text_audio.py scripts/compute_v2_text_audio_models.json \
     scripts/compute_v2_phone_embedding.py scripts/compute_v2_phone_embedding_models.json \
     scripts/compute_v2_model_installations.json scripts/compute_v2_modal_server.py /app/

# The orchestrator supplies the class module, action and task-scoped paths.
ENTRYPOINT ["python"]
CMD ["compute_v2_modal_server.py"]

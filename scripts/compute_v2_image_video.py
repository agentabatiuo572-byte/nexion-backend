#!/usr/bin/env python3
"""BK-SDM image / Text2Video-Zero execution and independent recomputation.

Call execute and verify in separate, bounded processes using the same pinned
runtime image. A successful return does not prove that the process has exited.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import tempfile
import time


MODEL = json.loads(Path(__file__).with_name('compute_v2_image_video_models.json').read_text(encoding='utf-8'))
RUNTIMES = {'IG': 'bk-sdm-tiny-image-v1', 'VG': 'bk-sdm-tiny-video-v1'}
BINDING_FIELDS = ('taskNo', 'generation', 'inputHash', 'taskClass', 'runtimeKey', 'modelRevision', 'workload', 'seed')


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def check_task(task):
    """Validate untrusted task input once, before loading weights or allocating frames."""
    if task.get('taskClass') not in RUNTIMES or task.get('runtimeKey') != RUNTIMES[task['taskClass']]:
        raise ValueError('TASK_RUNTIME_INVALID')
    if task.get('modelRevision') != MODEL['revision']:
        raise ValueError('TASK_MODEL_REVISION_INVALID')
    if not isinstance(task.get('taskNo'), str) or not 1 <= len(task['taskNo']) <= 128:
        raise ValueError('TASK_ID_INVALID')
    if type(task.get('generation')) is not int or task['generation'] < 1:
        raise ValueError('TASK_GENERATION_INVALID')
    value = task.get('inputHash', '')
    if not isinstance(value, str) or len(value) != 64 or any(char not in '0123456789abcdef' for char in value):
        raise ValueError('TASK_INPUT_HASH_INVALID')
    if type(task.get('seed')) is not int or not 0 <= task['seed'] <= 2**32 - 1:
        raise ValueError('TASK_SEED_INVALID')
    workload = task.get('workload')
    keys = {'prompt', 'width', 'height', 'steps'} | ({'frames', 'fps'} if task['taskClass'] == 'VG' else set())
    if not isinstance(workload, dict) or set(workload) != keys:
        raise ValueError('TASK_WORKLOAD_INVALID')
    if not isinstance(workload['prompt'], str) or not 1 <= len(workload['prompt'].strip()) <= 1000:
        raise ValueError('TASK_PROMPT_INVALID')
    if any(type(workload[key]) is not int or not 256 <= workload[key] <= 512 or workload[key] % 64
           for key in ('width', 'height')):
        raise ValueError('TASK_DIMENSIONS_INVALID')
    if type(workload['steps']) is not int or not 8 <= workload['steps'] <= 32:
        raise ValueError('TASK_STEPS_INVALID')
    if task['taskClass'] == 'VG' and (type(workload['frames']) is not int or not 4 <= workload['frames'] <= 8
                                    or type(workload['fps']) is not int or not 1 <= workload['fps'] <= 16):
        raise ValueError('TASK_VIDEO_WORKLOAD_INVALID')
    return hashlib.sha256(canonical({key: task[key] for key in BINDING_FIELDS})).hexdigest()


def check_model(model_root):
    model_root = Path(model_root).resolve(strict=True)
    for name, expected in MODEL['files'].items():
        path = (model_root / name).resolve(strict=True)
        if not path.is_relative_to(model_root) or not path.is_file() or digest(path) != expected:
            raise ValueError('MODEL_FILE_DIGEST_INVALID:' + name)
    return model_root


def run_model(task, model_root, device):
    # Set before importing torch or creating a CUDA context. Each operation runs
    # in a fresh process, with the same explicit device used by its validator.
    os.environ['CUBLAS_WORKSPACE_CONFIG'] = ':4096:8'
    import numpy as np
    import torch
    from diffusers import DDIMScheduler, StableDiffusionPipeline, TextToVideoZeroPipeline

    if device == 'cuda' and not torch.cuda.is_available():
        raise ValueError('CONFIGURED_CUDA_DEVICE_UNAVAILABLE')
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    workload = task['workload']
    pipeline_class = StableDiffusionPipeline if task['taskClass'] == 'IG' else TextToVideoZeroPipeline
    pipe = pipeline_class.from_pretrained(str(model_root), torch_dtype=torch.float32,
                                          local_files_only=True, use_safetensors=True, low_cpu_mem_usage=True)
    pipe.to(device)
    pipe.scheduler = DDIMScheduler.from_config(pipe.scheduler.config)
    pipe.set_progress_bar_config(disable=True)
    pipe.enable_vae_slicing()
    # T2V-Zero's cross-frame attention must remain installed; attention slicing
    # would replace that processor and silently remove temporal conditioning.
    if task['taskClass'] == 'IG':
        pipe.enable_attention_slicing()
    kwargs = {'prompt': workload['prompt'], 'width': workload['width'], 'height': workload['height'],
              'num_inference_steps': workload['steps'], 'guidance_scale': 7.5,
              'generator': torch.Generator(device=device).manual_seed(task['seed'])}
    if task['taskClass'] == 'VG':
        kwargs.update(video_length=workload['frames'], t0=workload['steps'] - 4, t1=workload['steps'] - 2,
                      motion_field_strength_x=12.0, motion_field_strength_y=12.0, output_type='np')
    with torch.inference_mode():
        output = pipe(**kwargs)
    if output.nsfw_content_detected is not None and any(output.nsfw_content_detected):
        raise ValueError('MODEL_OUTPUT_REJECTED_BY_SAFETY_CHECKER')
    if task['taskClass'] == 'IG':
        frames = np.asarray(output.images[0].convert('RGB'), dtype=np.uint8)[None, ...]
    else:
        frames = np.rint(np.clip(output.images, 0, 1) * 255).astype(np.uint8)
    return frames


def frame_metrics(frames):
    import numpy as np

    frame_hashes = [hashlib.sha256(frame.tobytes()).hexdigest() for frame in frames]
    spatial_std = [round(float(frame.std()), 6) for frame in frames]
    if any(value <= 1.0 for value in spatial_std):
        raise ValueError('MODEL_OUTPUT_BLANK')
    metrics = {'frameCount': len(frames), 'width': int(frames.shape[2]), 'height': int(frames.shape[1]),
               'frameSha256': frame_hashes, 'spatialStd': spatial_std}
    if len(frames) > 1:
        changes = [round(float(np.abs(frames[index].astype(np.int16) - frames[index - 1]).mean()), 6)
                   for index in range(1, len(frames))]
        if len(set(frame_hashes)) != len(frames) or any(value <= 0 for value in changes):
            raise ValueError('MODEL_VIDEO_STATIC')
        metrics['consecutiveFrameMeanAbsoluteDifference'] = changes
    return metrics


def execute(task: dict, model_root: Path, output_dir: Path) -> dict:
    binding = check_task(task)
    device = os.environ.get('COMPUTE_DEVICE')
    if device not in ('cpu', 'cuda'):
        raise ValueError('EXPLICIT_COMPUTE_DEVICE_REQUIRED')
    model_root = check_model(model_root)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    frames = run_model(task, model_root, device)
    metrics = frame_metrics(frames)
    metrics.update(modelId=MODEL['modelId'], inferenceSteps=task['workload']['steps'], scheduler='DDIM',
                   guidanceScale=7.5, device=device, dtype='float32', torchThreads=2,
                   runtimeVersions={name: importlib.metadata.version(name) for name in MODEL['dependencies']})
    if task['taskClass'] == 'IG':
        from PIL import Image, PngImagePlugin

        path, media_type = output_dir / 'result.png', 'image/png'
        info = PngImagePlugin.PngInfo()
        info.add_text('uvel-task-binding', binding)
        Image.fromarray(frames[0]).save(path, pnginfo=info)
    else:
        import imageio_ffmpeg

        path, media_type = output_dir / 'result.mp4', 'video/mp4'
        workload = task['workload']
        writer = imageio_ffmpeg.write_frames(str(path), (workload['width'], workload['height']),
                                            fps=workload['fps'], codec='libx264', pix_fmt_in='rgb24',
                                            pix_fmt_out='yuv420p', macro_block_size=1, ffmpeg_log_level='error',
                                            quality=None,
                                            output_params=['-crf', '18', '-threads', '1', '-metadata',
                                                           'comment=uvel-task-binding:' + binding,
                                                           '-movflags', '+faststart'])
        writer.send(None)
        try:
            for frame in frames:
                writer.send(frame.tobytes())
        finally:
            writer.close()
        metrics.update(fps=workload['fps'], durationSeconds=workload['frames'] / workload['fps'],
                       t0=workload['steps'] - 4, t1=workload['steps'] - 2,
                       motionFieldStrengthX=12.0, motionFieldStrengthY=12.0, videoMethod='Text2Video-Zero')
    metrics['elapsedSeconds'] = round(time.monotonic() - started, 6)
    return {'schemaVersion': 1, 'taskClass': task['taskClass'], 'runtimeKey': task['runtimeKey'],
            'modelRevision': task['modelRevision'], 'inputHash': task['inputHash'], 'taskBindingHash': binding,
            'artifact': {'path': path.name, 'mediaType': media_type, 'sha256': digest(path), 'sizeBytes': path.stat().st_size},
            'metrics': metrics}


def verify(task: dict, result: dict, model_root: Path, artifact_dir: Path) -> dict:
    """Recompute in the caller's independent validator process; never trust worker metrics."""
    binding = check_task(task)
    for key, value in {'schemaVersion': 1, 'taskClass': task['taskClass'], 'runtimeKey': task['runtimeKey'],
                       'modelRevision': task['modelRevision'], 'inputHash': task['inputHash'],
                       'taskBindingHash': binding}.items():
        if result.get(key) != value:
            raise ValueError('RESULT_BINDING_INVALID:' + key)
    artifact = result.get('artifact')
    name, media_type = ('result.png', 'image/png') if task['taskClass'] == 'IG' else ('result.mp4', 'video/mp4')
    if not isinstance(artifact, dict) or artifact.get('path') != name or artifact.get('mediaType') != media_type:
        raise ValueError('RESULT_ARTIFACT_INVALID')
    root = Path(artifact_dir).resolve(strict=True)
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file() or not 0 < path.stat().st_size <= 64 * 1024 * 1024:
        raise ValueError('RESULT_ARTIFACT_PATH_OR_SIZE_INVALID')
    if artifact.get('sizeBytes') != path.stat().st_size or artifact.get('sha256') != digest(path):
        raise ValueError('RESULT_ARTIFACT_DIGEST_INVALID')
    import numpy as np

    if task['taskClass'] == 'IG':
        from PIL import Image

        with Image.open(path) as image:
            image.load()
            if image.format != 'PNG' or image.mode != 'RGB' or image.info.get('uvel-task-binding') != binding:
                raise ValueError('RESULT_PNG_INVALID')
            frames = np.asarray(image)[None, ...]
    else:
        import imageio_ffmpeg

        reader = imageio_ffmpeg.read_frames(str(path), pix_fmt='rgb24', output_params=['-threads', '1'])
        meta = next(reader)
        workload = task['workload']
        if meta['size'] != (workload['width'], workload['height']) or abs(meta['fps'] - workload['fps']) > 0.001:
            reader.close()
            raise ValueError('RESULT_VIDEO_GEOMETRY_OR_TIMING_INVALID')
        decoded = []
        try:
            for raw in reader:
                if len(decoded) >= workload['frames']:
                    raise ValueError('RESULT_VIDEO_TOO_MANY_FRAMES')
                decoded.append(np.frombuffer(raw, dtype=np.uint8).reshape(workload['height'], workload['width'], 3))
        finally:
            reader.close()
        frames = np.asarray(decoded)
    decoded_metrics = frame_metrics(frames)
    expected_count = 1 if task['taskClass'] == 'IG' else task['workload']['frames']
    if (decoded_metrics['frameCount'], decoded_metrics['width'], decoded_metrics['height']) != (
            expected_count, task['workload']['width'], task['workload']['height']):
        raise ValueError('RESULT_FRAME_GEOMETRY_INVALID')
    # ponytail: exact same-device rerun is deliberately costly but proves real model work;
    # replace it only with an explicitly approved, measured probabilistic verifier.
    with tempfile.TemporaryDirectory(prefix='uvel-image-video-verify-') as temporary:
        expected = execute(task, model_root, Path(temporary))
    if expected['artifact']['sha256'] != artifact['sha256']:
        raise ValueError('RESULT_RECOMPUTATION_MISMATCH')
    expected_metrics, reported_metrics = expected['metrics'].copy(), result.get('metrics', {}).copy()
    expected_metrics.pop('elapsedSeconds')
    reported_metrics.pop('elapsedSeconds', None)
    if reported_metrics != expected_metrics:
        raise ValueError('RESULT_METRICS_INVALID')
    return {'schemaVersion': 1, 'verified': True, 'taskClass': task['taskClass'], 'inputHash': task['inputHash'],
            'verificationMethod': 'independent-model-recompute-and-media-decode-v1',
            'taskBindingHash': binding, 'artifactSha256': artifact['sha256'], 'metrics': decoded_metrics}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('execute', 'verify'))
    parser.add_argument('--task-file', required=True)
    parser.add_argument('--model-root', required=True)
    parser.add_argument('--artifact-dir', required=True)
    parser.add_argument('--result-file', required=True)
    args = parser.parse_args()
    task = json.loads(Path(args.task_file).read_text(encoding='utf-8'))
    if args.operation == 'execute':
        result = execute(task, Path(args.model_root), Path(args.artifact_dir))
        Path(args.result_file).write_bytes(canonical(result))
    else:
        result = verify(task, json.loads(Path(args.result_file).read_text(encoding='utf-8')),
                        Path(args.model_root), Path(args.artifact_dir))
    print(canonical(result).decode('utf-8'), flush=True)


if __name__ == '__main__':
    main()

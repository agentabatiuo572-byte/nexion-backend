#!/usr/bin/env python3
"""Real MiniLM phone EM and independent pinned ONNX CPU replay; no network or settlement writes."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re

REGISTRY = json.loads(Path(__file__).with_name('compute_v2_phone_embedding_models.json').read_text(encoding='utf-8'))
BINDING_FIELDS = ('taskNo', 'generation', 'inputHash', 'taskClass', 'runtimeKey', 'modelRevision', 'workload', 'seed')
FILENAME = 'embedding.f32'
MEDIA_TYPE = 'application/octet-stream'


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def file_digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def task_binding_hash(task):
    return hashlib.sha256(canonical({key: task[key] for key in BINDING_FIELDS})).hexdigest()


def validate(task, model_root):
    require(isinstance(task, dict) and all(key in task for key in BINDING_FIELDS), 'TASK_FIELDS_REQUIRED')
    require(task['taskClass'] == 'EM' and task['runtimeKey'] == REGISTRY['runtimeKey'], 'PHONE_RUNTIME_INVALID')
    model = REGISTRY['model']
    require(task['modelRevision'] == model['revision'], 'MODEL_REVISION_INVALID')
    require(isinstance(task['taskNo'], str) and re.fullmatch(r'CV2-[0-9A-F]{32}', task['taskNo']), 'TASK_NUMBER_INVALID')
    require(type(task['generation']) is int and task['generation'] > 0, 'GENERATION_INVALID')
    require(isinstance(task['inputHash'], str) and re.fullmatch(r'[0-9a-f]{64}', task['inputHash']), 'INPUT_HASH_INVALID')
    require(type(task['seed']) is int and 0 <= task['seed'] <= 2147483647, 'SEED_INVALID')
    work = task['workload']
    required_fields = {'inputIds', 'attentionMask', 'tokenTypeIds'}
    require(isinstance(work, dict) and set(work) in (required_fields, required_fields | {'texts'}), 'PHONE_WORKLOAD_FIELDS_INVALID')
    seq = None
    for name, maximum in (('inputIds', model['vocabularySize'] - 1), ('attentionMask', 1), ('tokenTypeIds', 1)):
        rows = work[name]
        require(isinstance(rows, list) and len(rows) == 2, 'PHONE_BATCH_INVALID')
        for row in rows:
            require(isinstance(row, list) and 2 <= len(row) <= 128, 'PHONE_SEQUENCE_INVALID')
            if seq is None:
                seq = len(row)
            require(len(row) == seq and all(type(value) is int and 0 <= value <= maximum for value in row),
                    'PHONE_TENSOR_INVALID:' + name)
    require(all(sum(row) >= 2 for row in work['attentionMask']), 'PHONE_ATTENTION_EMPTY')
    if 'texts' in work:
        require(isinstance(work['texts'], list) and len(work['texts']) == 2
                and all(isinstance(text, str) and 1 <= len(text) <= 2048 for text in work['texts']), 'PHONE_TEXTS_INVALID')
    model_path = model_root / model['file']
    require(model_path.is_file() and not model_path.is_symlink() and file_digest(model_path) == model['sha256'], 'PHONE_MODEL_DIGEST_INVALID')
    for name, expected in REGISTRY['dependencies'].items():
        require(importlib.metadata.version(name) == expected, 'PHONE_RUNTIME_VERSION_INVALID:' + name)
    return model_path, seq


def infer(task, model_root):
    model_path, seq = validate(task, model_root)
    import numpy as np
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    session = ort.InferenceSession(str(model_path), sess_options=options, providers=['CPUExecutionProvider'])
    work = task['workload']
    tensors = {name: np.asarray(work[key], dtype=np.int64) for name, key in (
        ('input_ids', 'inputIds'), ('attention_mask', 'attentionMask'), ('token_type_ids', 'tokenTypeIds'))}
    hidden = session.run(None, tensors)[0]
    require(hidden.shape == (2, seq, 384) and np.isfinite(hidden).all(), 'PHONE_ENCODER_OUTPUT_INVALID')
    mask = tensors['attention_mask'][..., None].astype(np.float32)
    pooled = (hidden * mask).sum(axis=1) / mask.sum(axis=1)
    norm = np.linalg.norm(pooled, axis=1, keepdims=True)
    require((norm > 0).all(), 'PHONE_ZERO_EMBEDDING')
    return (pooled / norm).astype('<f4'), seq


def execute(task, model_root, output_dir):
    vectors, seq = infer(task, Path(model_root))
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    artifact = output_dir / FILENAME
    artifact.write_bytes(vectors.tobytes(order='C'))
    return {'schemaVersion': 1, 'taskClass': 'EM', 'runtimeKey': REGISTRY['runtimeKey'],
            'modelRevision': task['modelRevision'], 'inputHash': task['inputHash'], 'taskBindingHash': task_binding_hash(task),
            'artifact': {'path': FILENAME, 'mediaType': MEDIA_TYPE, 'sha256': file_digest(artifact), 'sizeBytes': 3072},
            'metrics': {'batchSize': 2, 'sequenceLength': seq, 'dimensions': 384, 'cpuThreads': 1}}


def verify(task, result, model_root, artifact_dir):
    import numpy as np
    require(isinstance(result, dict) and result.get('schemaVersion') == 1, 'RESULT_SCHEMA_INVALID')
    for key in ('taskClass', 'runtimeKey', 'modelRevision', 'inputHash'):
        require(result.get(key) == task.get(key), 'RESULT_BINDING_INVALID:' + key)
    require(result.get('taskBindingHash') == task_binding_hash(task), 'RESULT_TASK_BINDING_INVALID')
    descriptor = result.get('artifact')
    require(isinstance(descriptor, dict) and descriptor.get('path') == FILENAME
            and descriptor.get('mediaType') == MEDIA_TYPE and descriptor.get('sizeBytes') == 3072, 'ARTIFACT_DESCRIPTOR_INVALID')
    artifact = Path(artifact_dir) / FILENAME
    require(artifact.is_file() and not artifact.is_symlink() and artifact.stat().st_size == 3072
            and file_digest(artifact) == descriptor.get('sha256'), 'ARTIFACT_DIGEST_INVALID')
    observed = np.frombuffer(artifact.read_bytes(), dtype='<f4').reshape(2, 384)
    require(np.isfinite(observed).all(), 'PHONE_NONFINITE_EMBEDDING')
    expected, seq = infer(task, Path(model_root))
    metrics = result.get('metrics')
    expected_metrics = {'batchSize': 2, 'sequenceLength': seq, 'dimensions': 384, 'cpuThreads': 1}
    require(isinstance(metrics, dict) and metrics == expected_metrics and all(type(value) is int for value in metrics.values()),
            'PHONE_METRICS_INVALID')
    gates = REGISTRY['verification']
    cosine = (observed * expected).sum(axis=1) / (np.linalg.norm(observed, axis=1) * np.linalg.norm(expected, axis=1))
    maximum_error = float(np.max(np.abs(observed - expected)))
    require(np.allclose(observed, expected, atol=gates['absoluteTolerance'], rtol=gates['relativeTolerance'])
            and (cosine >= gates['minimumCosine']).all(), 'PHONE_INDEPENDENT_REPLAY_MISMATCH')
    return {'schemaVersion': 1, 'verified': True, 'taskClass': 'EM', 'inputHash': task['inputHash'],
            'taskBindingHash': result['taskBindingHash'], 'artifactSha256': descriptor['sha256'],
            'verificationMethod': 'independent-pinned-onnx-cpu-replay-v1',
            'metrics': {'maxAbsoluteError': maximum_error, 'minimumCosine': float(cosine.min()), **metrics}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('execute', 'verify'))
    parser.add_argument('--task-file', required=True)
    parser.add_argument('--model-root', required=True)
    parser.add_argument('--artifact-dir', required=True)
    parser.add_argument('--result-file', required=True)
    args = parser.parse_args()
    task = json.loads(Path(args.task_file).read_text(encoding='utf-8'))
    if args.action == 'execute':
        result = execute(task, Path(args.model_root), Path(args.artifact_dir))
        Path(args.result_file).write_bytes(canonical(result))
    else:
        result = verify(task, json.loads(Path(args.result_file).read_text(encoding='utf-8')),
                        Path(args.model_root), Path(args.artifact_dir))
    print(canonical(result).decode(), flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Pinned CPU LL, LoRA FT and Whisper SP runtimes; verification replays the work.

model_root is the installed model snapshot, not a Hugging Face cache root.
The orchestrator runs execute and verify in separate, resource-limited processes.
No network/model download, privileged operation or financial action occurs here.
"""
import argparse
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import re
import tempfile
import wave
import zipfile


REGISTRY = json.loads(Path(__file__).with_name('compute_v2_text_audio_models.json').read_text(encoding='utf-8'))
RUNTIMES = {
    'LL': ('ll-smollm2-135m-instruct-v1', 'smollm2-135m-instruct', 'tokens.json', 'application/json'),
    'FT': ('ft-smollm2-135m-lora-v1', 'smollm2-135m-instruct', 'adapter.zip', 'application/zip'),
    'SP': ('sp-whisper-tiny-v1', 'whisper-tiny', 'transcript.json', 'application/json'),
}
BINDING_FIELDS = ('taskNo', 'generation', 'inputHash', 'taskClass', 'runtimeKey', 'modelRevision', 'workload', 'seed')


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
    require(task['taskClass'] in RUNTIMES, 'TASK_CLASS_UNSUPPORTED')
    runtime_key, model_key, _, _ = RUNTIMES[task['taskClass']]
    model = REGISTRY['models'][model_key]
    require(task['runtimeKey'] == runtime_key and task['modelRevision'] == model['revision'], 'MODEL_BINDING_INVALID')
    require(isinstance(task['taskNo'], str) and re.fullmatch(r'CV2-[0-9A-F]{32}', task['taskNo']), 'TASK_NUMBER_INVALID')
    require(type(task['generation']) is int and task['generation'] > 0, 'GENERATION_INVALID')
    require(isinstance(task['inputHash'], str) and re.fullmatch(r'[0-9a-f]{64}', task['inputHash']), 'INPUT_HASH_INVALID')
    require(type(task['seed']) is int and 0 <= task['seed'] <= 2147483647, 'SEED_INVALID')
    work = task['workload']
    require(isinstance(work, dict), 'WORKLOAD_REQUIRED')
    if task['taskClass'] == 'LL':
        require(set(work) == {'prompt', 'maxNewTokens'}, 'LL_WORKLOAD_FIELDS_INVALID')
        require(isinstance(work['prompt'], str) and 1 <= len(work['prompt']) <= 2000, 'PROMPT_INVALID')
        require(type(work['maxNewTokens']) is int and 8 <= work['maxNewTokens'] <= 128, 'TOKEN_BUDGET_INVALID')
    elif task['taskClass'] == 'FT':
        require(set(work) == {'trainingTexts', 'evaluationText', 'maxSequenceLength', 'steps', 'learningRate'}, 'FT_WORKLOAD_FIELDS_INVALID')
        require(isinstance(work['trainingTexts'], list) and 2 <= len(work['trainingTexts']) <= 8
                and all(isinstance(text, str) and 8 <= len(text) <= 1024 for text in work['trainingTexts']), 'TRAINING_TEXTS_INVALID')
        require(isinstance(work['evaluationText'], str) and 8 <= len(work['evaluationText']) <= 1024
                and work['evaluationText'] not in work['trainingTexts'], 'EVALUATION_TEXT_INVALID')
        require(type(work['maxSequenceLength']) is int and 32 <= work['maxSequenceLength'] <= 128, 'SEQUENCE_LENGTH_INVALID')
        require(type(work['steps']) is int and 2 <= work['steps'] <= 8, 'TRAINING_STEPS_INVALID')
        require(type(work['learningRate']) in (int, float) and math.isfinite(work['learningRate'])
                and 0.0001 <= work['learningRate'] <= 0.01, 'LEARNING_RATE_INVALID')
    else:
        base_fields = {'audioPath', 'audioSha256', 'language', 'maxNewTokens'}
        require(set(work) in (base_fields, base_fields | {'referenceText', 'maxWordErrorRate'}), 'SP_WORKLOAD_FIELDS_INVALID')
        require(isinstance(work['audioPath'], str) and 1 <= len(work['audioPath']) <= 256, 'AUDIO_PATH_INVALID')
        relative = Path(work['audioPath'])
        require(not relative.is_absolute() and '..' not in relative.parts and relative.suffix.lower() == '.wav', 'AUDIO_PATH_INVALID')
        require(isinstance(work['audioSha256'], str) and re.fullmatch(r'[0-9a-f]{64}', work['audioSha256']), 'AUDIO_HASH_INVALID')
        require(work['language'] == 'english', 'AUDIO_LANGUAGE_UNSUPPORTED')
        require(type(work['maxNewTokens']) is int and 16 <= work['maxNewTokens'] <= 128, 'TOKEN_BUDGET_INVALID')
        if 'referenceText' in work:
            require(isinstance(work['referenceText'], str) and 1 <= len(work['referenceText']) <= 4000, 'REFERENCE_TEXT_INVALID')
            require(type(work['maxWordErrorRate']) in (int, float) and math.isfinite(work['maxWordErrorRate'])
                    and 0 <= work['maxWordErrorRate'] <= 0.5, 'WORD_ERROR_GATE_INVALID')
        audio_path = (model_root / relative).resolve(strict=True)
        require(audio_path.is_relative_to(model_root.resolve(strict=True)) and audio_path.is_file(), 'AUDIO_PATH_ESCAPES_MODEL_ROOT')
        require(audio_path.stat().st_size <= 1000000 and file_digest(audio_path) == work['audioSha256'], 'AUDIO_DIGEST_OR_SIZE_INVALID')
    require(not (model_root / 'adapter_config.json').exists(), 'BASE_MODEL_MUST_NOT_HAVE_ADAPTER')
    for name, expected in model['files'].items():
        require(file_digest(model_root / name) == expected, 'MODEL_FILE_DIGEST_INVALID:' + name)
    return model


def execute(task: dict, model_root: Path, output_dir: Path) -> dict:
    """Run one bounded real-model workload and return a single artifact descriptor."""
    model_root, output_dir = Path(model_root), Path(output_dir)
    model_info = validate(task, model_root)
    import numpy as np
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, WhisperForConditionalGeneration, WhisperProcessor

    # ponytail: fixed two CPU threads for deterministic replay; scale only after a measured capacity change.
    torch.set_num_threads(2)
    torch.manual_seed(task['seed'])
    torch.use_deterministic_algorithms(True)
    np.random.seed(task['seed'])
    output_dir.mkdir(parents=True, exist_ok=True)
    work = task['workload']
    task_hash = task_binding_hash(task)
    versions = {name: importlib.metadata.version(name) for name in REGISTRY['dependencies']}
    require(all(versions[name].split('+')[0] == version for name, version in REGISTRY['dependencies'].items()),
            'RUNTIME_DEPENDENCY_VERSION_MISMATCH')
    payload = {'schemaVersion': 1, 'taskBindingHash': task_hash, 'modelId': model_info['modelId'],
               'modelRevision': model_info['revision'], 'runtimeVersions': versions}
    load_options = {'local_files_only': True, 'trust_remote_code': False}
    if task['taskClass'] in ('LL', 'FT'):
        tokenizer = AutoTokenizer.from_pretrained(model_root, **load_options)
        tokenizer.pad_token = tokenizer.eos_token
        model = AutoModelForCausalLM.from_pretrained(model_root, torch_dtype=torch.float32,
                                                    attn_implementation='eager', use_safetensors=True, **load_options)
        if task['taskClass'] == 'LL':
            rendered = tokenizer.apply_chat_template([{'role': 'user', 'content': work['prompt']}],
                                                     tokenize=False, add_generation_prompt=True)
            inputs = tokenizer(rendered, return_tensors='pt')
            input_tokens = inputs['input_ids'].shape[1]
            require(input_tokens <= 256, 'PROMPT_TOKEN_LIMIT_EXCEEDED')
            model.eval()
            with torch.inference_mode():
                token_ids = model.generate(**inputs, do_sample=False, num_beams=1, max_new_tokens=work['maxNewTokens'],
                                           pad_token_id=tokenizer.eos_token_id)[0, input_tokens:].tolist()
            text = tokenizer.decode(token_ids, skip_special_tokens=True)
            require(token_ids and text.strip(), 'EMPTY_GENERATION')
            metrics = {'inputTokens': input_tokens, 'outputTokens': len(token_ids), 'cpuThreads': 2}
            payload.update(tokenIds=token_ids, text=text, metrics=metrics)
        else:
            from peft import LoraConfig, PeftModel, TaskType, get_peft_model
            from peft.utils.save_and_load import get_peft_model_state_dict
            model.config.use_cache = False
            model = get_peft_model(model, LoraConfig(task_type=TaskType.CAUSAL_LM, r=4, lora_alpha=8,
                                                    target_modules=['q_proj', 'v_proj'], lora_dropout=0.0, bias='none'))
            model.peft_config['default'].base_model_name_or_path = model_info['modelId']
            model.peft_config['default'].revision = model_info['revision']
            initial = {name: value.detach().clone() for name, value in model.named_parameters() if value.requires_grad}
            batch = tokenizer(work['trainingTexts'], return_tensors='pt', padding=True, truncation=True,
                              max_length=work['maxSequenceLength'])
            labels = batch['input_ids'].clone()
            labels[batch['attention_mask'] == 0] = -100
            require(all(int(value) >= 2 for value in batch['attention_mask'].sum(dim=1)), 'TRAINING_TOKENS_TOO_SHORT')
            evaluation = tokenizer(work['evaluationText'], return_tensors='pt', truncation=True,
                                   max_length=work['maxSequenceLength'])
            require(evaluation['input_ids'].shape[1] >= 2, 'EVALUATION_TOKENS_TOO_SHORT')
            model.eval()
            with torch.no_grad():
                initial_loss = float(model(**batch, labels=labels).loss)
                initial_eval_loss = float(model(**evaluation, labels=evaluation['input_ids']).loss)
            optimizer = torch.optim.AdamW([value for value in model.parameters() if value.requires_grad],
                                          lr=work['learningRate'], weight_decay=0.0)
            step_losses = []
            model.train()
            for _ in range(work['steps']):
                optimizer.zero_grad(set_to_none=True)
                loss = model(**batch, labels=labels).loss
                require(bool(torch.isfinite(loss)), 'TRAINING_LOSS_NONFINITE')
                loss.backward()
                torch.nn.utils.clip_grad_norm_([value for value in model.parameters() if value.requires_grad], 1.0,
                                               error_if_nonfinite=True)
                optimizer.step()
                step_losses.append(float(loss.detach()))
            model.eval()
            with torch.no_grad():
                final_loss = float(model(**batch, labels=labels).loss)
                final_eval_loss = float(model(**evaluation, labels=evaluation['input_ids']).loss)
            update_squared = sum(float((value.detach() - initial[name]).double().square().sum())
                                 for name, value in model.named_parameters() if value.requires_grad)
            require(math.isfinite(final_loss) and final_loss < initial_loss and update_squared > 0, 'TRAINING_DID_NOT_IMPROVE')
            state = {name: value.detach().cpu().clone() for name, value in get_peft_model_state_dict(model).items()}
            metrics = {'steps': work['steps'], 'trainingExamples': len(work['trainingTexts']),
                       'trainingTokens': int(batch['attention_mask'].sum()), 'trainableParameters': sum(value.numel() for value in initial.values()),
                       'initialLoss': initial_loss, 'finalLoss': final_loss, 'initialEvaluationLoss': initial_eval_loss,
                       'finalEvaluationLoss': final_eval_loss, 'parameterUpdateL2': math.sqrt(update_squared), 'cpuThreads': 2}
            require(all(math.isfinite(value) for value in metrics.values()), 'TRAINING_METRICS_NONFINITE')
            payload.update(trainingDataHash=hashlib.sha256(canonical(work['trainingTexts'])).hexdigest(),
                           evaluationDataHash=hashlib.sha256(work['evaluationText'].encode()).hexdigest(),
                           stepLosses=step_losses, metrics=metrics)
            with tempfile.TemporaryDirectory(prefix='compute-v2-adapter-') as temporary:
                adapter_dir = Path(temporary)
                model.save_pretrained(adapter_dir, safe_serialization=True, save_embedding_layers=False)
                del loss, optimizer, model, initial
                # Reload on the pinned pretrained base, so a non-loadable adapter never becomes a result.
                base = AutoModelForCausalLM.from_pretrained(model_root, torch_dtype=torch.float32,
                                                           attn_implementation='eager', use_safetensors=True, **load_options)
                reloaded = PeftModel.from_pretrained(base, adapter_dir, is_trainable=False, local_files_only=True)
                loaded_state = get_peft_model_state_dict(reloaded)
                require(set(loaded_state) == set(state) and all(torch.equal(loaded_state[key], state[key]) for key in state),
                        'ADAPTER_RELOAD_PARAMETER_MISMATCH')
                reloaded.eval()
                with torch.no_grad():
                    reloaded_loss = float(reloaded(**batch, labels=labels).loss)
                require(math.isclose(reloaded_loss, final_loss, rel_tol=1e-6, abs_tol=1e-6), 'ADAPTER_RELOAD_LOSS_MISMATCH')
                payload['adapterReloaded'] = True
                (adapter_dir / 'training.json').write_bytes(canonical(payload))
                with zipfile.ZipFile(output_dir / 'adapter.zip', 'w', compression=zipfile.ZIP_STORED) as archive:
                    for name in ('adapter_config.json', 'adapter_model.safetensors', 'training.json'):
                        archive.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), (adapter_dir / name).read_bytes())
    else:
        audio_path = (model_root / work['audioPath']).resolve(strict=True)
        with wave.open(str(audio_path), 'rb') as source:
            require(source.getnchannels() == 1 and source.getsampwidth() == 2 and source.getframerate() == 16000
                    and source.getcomptype() == 'NONE' and 1600 <= source.getnframes() <= 480000, 'AUDIO_FORMAT_INVALID')
            frame_count = source.getnframes()
            pcm = source.readframes(frame_count)
        require(len(pcm) == frame_count * 2, 'AUDIO_TRUNCATED')
        audio = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
        require(float(np.sqrt(np.mean(audio ** 2))) > 0.0001, 'AUDIO_SILENT')
        processor = WhisperProcessor.from_pretrained(model_root, **load_options)
        model = WhisperForConditionalGeneration.from_pretrained(model_root, torch_dtype=torch.float32,
                                                                attn_implementation='eager', use_safetensors=True, **load_options)
        model.eval()
        inputs = processor(audio, sampling_rate=16000, return_tensors='pt', return_attention_mask=True)
        with torch.inference_mode():
            generated = model.generate(**inputs, do_sample=False, num_beams=1, language=work['language'], task='transcribe',
                                       return_timestamps=True, max_new_tokens=work['maxNewTokens'])
        decoded = processor.batch_decode(generated, skip_special_tokens=True, output_offsets=True)[0]
        text = decoded['text']
        duration = frame_count / 16000.0
        require(text.strip() and decoded['offsets'], 'EMPTY_TRANSCRIPTION')
        offsets = [{'start': item['timestamp'][0], 'end': item['timestamp'][1], 'text': item['text']} for item in decoded['offsets']]
        previous_end = 0.0
        for item in offsets:
            require(item['start'] is not None and item['end'] is not None
                    and previous_end <= item['start'] < item['end'] <= duration + 1.0, 'TRANSCRIPTION_TIMING_INVALID')
            previous_end = item['end']
        metrics = {'sampleRate': 16000, 'sampleCount': frame_count, 'audioDurationSeconds': duration,
                   'outputTokens': len(generated[0]), 'segmentCount': len(offsets), 'cpuThreads': 2}
        if 'referenceText' in work:
            expected = re.findall(r'[a-z0-9]+', work['referenceText'].lower())
            actual = re.findall(r'[a-z0-9]+', text.lower())
            require(expected, 'REFERENCE_HAS_NO_WORDS')
            row = list(range(len(actual) + 1))
            for index, word in enumerate(expected, 1):
                next_row = [index]
                for column, observed in enumerate(actual, 1):
                    next_row.append(min(row[column] + 1, next_row[-1] + 1, row[column - 1] + (word != observed)))
                row = next_row
            metrics['wordErrorRate'] = row[-1] / len(expected)
            require(metrics['wordErrorRate'] <= work['maxWordErrorRate'], 'TRANSCRIPTION_QUALITY_GATE_FAILED')
        payload.update(audioSha256=work['audioSha256'], tokenIds=generated[0].tolist(), text=text,
                       segments=offsets, metrics=metrics)
    _, _, filename, media_type = RUNTIMES[task['taskClass']]
    artifact_path = output_dir / filename
    if task['taskClass'] != 'FT':
        artifact_path.write_bytes(canonical(payload))
    return {'schemaVersion': 1, 'taskClass': task['taskClass'], 'runtimeKey': task['runtimeKey'],
            'modelRevision': task['modelRevision'], 'inputHash': task['inputHash'], 'taskBindingHash': task_hash,
            'artifact': {'path': filename, 'mediaType': media_type, 'sha256': file_digest(artifact_path),
                         'sizeBytes': artifact_path.stat().st_size}, 'metrics': metrics}


def verify(task: dict, result: dict, model_root: Path, artifact_dir: Path) -> dict:
    """Verify artifact integrity, then independently replay frozen CPU model work."""
    model_root, artifact_dir = Path(model_root), Path(artifact_dir)
    require(isinstance(result, dict) and result.get('schemaVersion') == 1, 'RESULT_SCHEMA_INVALID')
    for field in ('taskClass', 'runtimeKey', 'modelRevision', 'inputHash'):
        require(result.get(field) == task.get(field), 'RESULT_BINDING_INVALID:' + field)
    require(result.get('taskBindingHash') == task_binding_hash(task), 'RESULT_TASK_BINDING_INVALID')
    require(task['taskClass'] in RUNTIMES, 'TASK_CLASS_UNSUPPORTED')
    _, _, filename, media_type = RUNTIMES[task['taskClass']]
    descriptor = result.get('artifact')
    require(isinstance(descriptor, dict) and descriptor.get('path') == filename and descriptor.get('mediaType') == media_type,
            'ARTIFACT_DESCRIPTOR_INVALID')
    artifact_path = artifact_dir / filename
    require(not artifact_path.is_symlink() and artifact_path.is_file() and artifact_path.stat().st_size <= 16000000,
            'ARTIFACT_FILE_INVALID')
    require(descriptor.get('sizeBytes') == artifact_path.stat().st_size and descriptor.get('sha256') == file_digest(artifact_path),
            'ARTIFACT_DIGEST_INVALID')
    # Fresh temporary output and fresh model instances: the producer's metrics are never the acceptance oracle.
    with tempfile.TemporaryDirectory(prefix='compute-v2-replay-') as temporary:
        replayed = execute(task, model_root, Path(temporary))
        require(result == replayed, 'INDEPENDENT_REPLAY_MISMATCH')
    return {'schemaVersion': 1, 'verified': True, 'taskClass': task['taskClass'], 'inputHash': task['inputHash'],
            'taskBindingHash': result['taskBindingHash'], 'artifactSha256': descriptor['sha256'],
            'verificationMethod': 'independent-pinned-cpu-replay-v1', 'metrics': result['metrics']}


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

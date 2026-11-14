# Bundled on-device model feasibility

Research date: 2026-11-12. Scope: a self-contained Android inference path for Sorted, with no server calls or first-run model download. This is a research assessment; no model has been trained, converted, installed, or benchmarked for this report.

## Selected direction

The user explicitly clarified that the selected scope includes all three techniques: use-case-specific distillation, pruning, and quantization.

- First task: category suggestions from merchant descriptions and the existing direction/payment-mode context, through `CategorySuggestionEngine`.
- Train a compact student for Sorted's fixed category vocabulary using reviewed labels and teacher supervision. An already distilled encoder is a starting checkpoint; fine-tuning it alone does not constitute new task-specific distillation.
- Prune the trained student incrementally, physically compact supported structures, and recover accuracy with supervised and teacher-assisted training. Measure the exported artifact rather than treating zero masks as a size or speed improvement.
- Quantize the recovered student to INT8 and compare its exported accuracy with the unpruned student, pruned floating-point student, and existing deterministic baseline.
- Ship the student, tokenizer, category mapping, and inference runtime with the Android app. The larger teacher is used during development and is not shipped or called by the app.
- Keep personal corrections on the device and preserve their priority. Use a local teacher or synthetic/sanitized approved examples for supervision; raw private messages are not sent to a teacher service.
- Keep uncertain suggestions subject to abstention and explicit acceptance. Arithmetic and transaction facts remain controlled by application logic.

Next deliverable: a broader reviewed evaluation corpus and baseline measurements, followed by a reproducible teacher/student training experiment. Model family, final size, and runtime remain evaluation decisions. Extraction and text generation are later experiments, outside the first model's task.

## Assessment

Feasible for bounded tasks such as category suggestions, merchant understanding, and eventually extraction of candidate fields from unfamiliar message formats. A useful category model can be much smaller than a conversational language model. Start with a distilled encoder classifier and INT8 quantization; evaluate a small generative model only if the task needs generation or flexible structured extraction.

The main uncertainty is useful accuracy on Sorted's actual inputs at acceptable coverage. Being able to load a model on a phone does not establish that it improves the app. New merchants, opaque UPI identifiers, payment gateways, multilingual descriptions, and ambiguous purchases require separate evaluation. When the source contains no evidence of the purchase category, a model cannot reliably recover it.

## Current repository fit

- `android/app/build.gradle.kts` sets minimum Android API 26 and target API 36. It currently declares no ML inference runtime.
- `engine/CategorySuggestion.kt` already provides `CategorySuggestionEngine`, a suggestion/abstention result, and an `ON_DEVICE_MODEL` source. This is an appropriate boundary for a first model adapter.
- `data/TransactionRepository.kt` already obtains suggestions from local correction examples. User rules and corrections must retain priority.
- `CategorySuggestionEvaluation.kt` contains 14 synthetic cases and measures coverage, precision when suggesting, and category-level results. This is a regression fixture, not a sufficient training or representative evaluation dataset.
- `docs/local-intelligence-plan.md` already calls for a compact classifier, local processing, deterministic fallbacks, and device measurements before introducing a runtime.
- Amounts, totals, eligibility, deduplication, and source records remain owned by existing application logic. Category suggestions do not change outgoing-total eligibility. Incoming refunds remain separate.

## Candidate approaches

The following weight sizes are **arithmetic estimates**, not converted artifact sizes or RAM measurements. Decimal MB is used. The calculation is parameter count multiplied by bits per weight divided by eight. Quantization scales, mixed-precision tensors, tokenizers, runtime libraries, metadata, and working memory add overhead.

| Approach | Illustrative weight storage | Capability and tradeoff |
| --- | --- | --- |
| Character n-gram linear classifier | Set a prototype artifact budget of 1–5 MB; feature count determines actual size | Strong cheap baseline for noisy merchant strings; limited contextual understanding |
| Custom 10–35M encoder, INT8 | 10–35 MB | Preferred experiment for category/type proposals; no text generation |
| MiniLM-L12-H384, 33M, INT8 | About 33 MB | Existing distilled encoder to fine-tune; English checkpoint needs language-specific evaluation |
| SmolLM2-135M, INT4 | About 67.5 MB | Tiny generative comparison; requires domain evaluation and likely fine-tuning |
| Gemma 3 270M, INT4 | About 135 MB | Candidate for short structured extraction; actual export can be materially larger |
| Generic 1B language model, INT4 | About 500 MB | Much larger deployment and memory commitment; evaluate only if smaller models fail a valuable task |

MiniLM's publisher documents a 33M distilled model that requires fine-tuning. SmolLM2 offers 135M, 360M, and 1.7B models. Google describes Gemma 3 270M as a specialist fine-tuning base, offers INT4 QAT checkpoints, and explicitly distinguishes it from models for complex conversations. These are comparison candidates, not an exhaustive or newest-model ranking. [MiniLM model card](https://huggingface.co/microsoft/MiniLM-L12-H384-uncased), [SmolLM2 model card](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct), [Gemma announcement](https://developers.googleblog.com/introducing-gemma-3-270m/).

Do not infer phone RAM requirements from weight storage. Classifiers need activations and runtime buffers; autoregressive models also need a context-dependent KV cache. Loading strategies can create extra copies. Measure whole-app proportional set size and peak native memory with the real runtime and tokenizer.

## Distillation, quantization, and pruning

These solve different problems:

- **Fine-tuning** teaches an existing model Sorted's categories and input patterns; it does not inherently shrink the model.
- **Distillation** trains a smaller student using a larger teacher's outputs, probabilities, or internal representations. A teacher can provide labels to a classifier; the student does not need to generate explanations.
- **Quantization** stores and computes with lower precision where supported. INT8 is a practical encoder starting point; INT4 is worth testing for generative models. The exported operations and phone backend determine actual speed and accuracy.
- **Pruning** removes weights or structures. Zeroing individual weights does not automatically reduce dense execution time or resident memory. Structured removal can reduce computation, but changes the architecture and requires quality recovery and export validation. Sparse execution helps only when the selected runtime has compatible kernels for those operations.

Selected sequence: establish task and baseline → distill task-specific behavior into a compact student → prune supported structures → recover accuracy → export and quantize → evaluate exported artifact. LoRA is a training technique, not a way to eliminate the base model at inference.

Google documents quantization and compression tradeoffs; its XNNPACK pruning tutorial demonstrates architecture-specific sparse acceleration for mostly convolutional models, rather than a universal transformer speedup. [Model optimization](https://developers.google.com/edge/litert/conversion/tensorflow/quantization/model_optimization), [Pruning for on-device inference](https://www.tensorflow.org/model_optimization/guide/pruning/pruning_for_on_device_inference).

## Runtime shortlist

| Runtime | Suitable experiment | Integration considerations |
| --- | --- | --- |
| Bundled ONNX Runtime Android | Fine-tuned encoder classifier | Java/Kotlin access; export and quantization operator compatibility must be verified; custom builds can trim unused operators |
| Standalone bundled LiteRT | Small classifier with a supported export | Compare conversion effort, CPU performance, and installed library size with ONNX Runtime |
| LiteRT-LM | Small generative model in supported `.litertlm` format | Kotlin API available; verify custom fine-tune conversion, exact runtime API/ABI requirements, and backend support |
| llama.cpp | Generative comparison using GGUF | Android example and native integration exist; budget for NDK/JNI maintenance and lifecycle management |

Start with CPU as the compatibility baseline. GPU/NPU acceleration is an optimization, not a requirement for core categorization. Pin runtime versions and verify compatibility on API 26 devices rather than assuming every current runtime/backend supports the app's full device range. Verify native 16 KB page-size support for relevant modern Android devices.

ONNX Runtime's documentation recommends measuring model size, binary size, latency, and power and describes custom operator builds. Google's LiteRT and LiteRT-LM documentation covers Android inference and Kotlin integration. llama.cpp supplies an Android application binding. [ONNX mobile](https://onnxruntime.ai/docs/tutorials/mobile/), [LiteRT Android](https://developers.google.com/edge/litert/android), [LiteRT-LM Android](https://developers.google.com/edge/litert-lm/android), [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).

Gemini Nano through ML Kit is an alternative for supported phones, but does not meet the strongest interpretation of a model owned and bundled by Sorted: the API relies on device-managed model availability and download checks. Keep it optional if explored. [ML Kit direct model access](https://developers.google.com/ml-kit/genai/prompt/android/get-started).

## What self-contained delivery requires

Ship the inference libraries, model weights, tokenizer/vocabulary, text normalization rules, label mapping, model/schema version, and any backend assets. Nothing should be fetched lazily during first inference. Prefer app assets for the first compact classifier. Use uncompressed model assets where required for mapping; if the runtime needs a filesystem path, provision a versioned app-private copy and account for duplicate disk storage.

For larger models, Google now documents Play for On-device AI, currently beta, with install-time AI packs available at app launch. Fast-follow and on-demand delivery do not guarantee offline availability immediately after installation. The simplest sideloadable APK path remains ordinary bundled assets; evaluate Play AI packs separately for distribution needs. [Play for On-device AI](https://developer.android.com/google/play/on-device-ai).

Self-contained inference does not make Gmail import or fresh exchange-rate retrieval work without a network. It makes interpretation of already available local data independent of a server. Model training can happen on a developer machine or controlled training infrastructure before release; training is not required on the user's phone. Offline inference also does not establish that every dependency is telemetry-free: inspect the selected runtime and verify network behavior.

## Proposed first task and architecture

Input: bounded normalized merchant text, a short description when available, direction, and payment mode. Output: a category ID or a valid category/type tuple plus a calibrated score, or abstention. Use a fixed app-owned vocabulary; reject unknown or incompatible output combinations. Category/type heads or mappings should be evaluated separately so that an uncertain type does not silently become an accounting decision.

Processing order:

1. Preserve user corrections and explicit rules.
2. Use reliable deterministic matches and local correction evidence.
3. Ask the bundled model for unresolved suggestions.
4. Validate the result and apply a threshold calibrated on held-out data.
5. Return a proposal through `CategorySuggestionEngine`; require the existing explicit acceptance before writing a category correction.
6. Abstain on insufficient evidence, unavailable runtime, load failure, timeout, or unsupported device. Imports and summaries continue.

Run inference on a bounded background dispatcher, with a reusable session, cancellation, input limits, and memory-pressure handling. Avoid loading a model for each transaction. Cache by normalized input plus model version. Importing thousands of messages should use rules and cached results first, then queue unresolved cases; benchmark sustained batches for heat and battery rather than extrapolating from one fast call.

Do not interpret a model-written `confidence: 0.98` as a calibrated probability. Use held-out calibration and per-category thresholds for a classifier. A generative model's JSON validity or token probability is not proof of semantic correctness. Merchant strings alone often cannot distinguish a pharmacy purchase from groceries at a multipurpose retailer.

Later experiments can use a span extraction model or bounded generator to propose merchant/amount/date spans for unfamiliar messages. Require source-span evidence and deterministic validation before promotion to transaction facts. This has a higher risk and a different evaluation contract from category suggestions; keep it outside the first implementation slice. For natural-language questions, map language to allowlisted application queries and calculate answers in Kotlin/SQL. Avoid unrestricted model-generated SQL.

## Data and training plan

The 14 existing synthetic cases are insufficient to choose a model. Start by expanding a manually reviewed evaluation set of approximately 1,000–2,000 varied examples, with meaningful support per important category. This is a planning starting point, not a statistically sufficient size for every precision claim. Build learning curves; a training set of several thousand to tens of thousands of reviewed or teacher-assisted examples is an experiment range, not a guaranteed requirement or success threshold.

- Cover banks, senders, SMS/email formats, gateway noise, abbreviations, unknown merchants, local languages and transliteration, personal transfers, investments, refunds, rewards, failed/pending alerts, promotions, and same-merchant category conflicts.
- Separate category evaluation from extraction and transaction-detection evaluation.
- Deduplicate and split by merchant families/message templates, with a later-time holdout where possible. Random splits of near-identical alerts will overstate generalization.
- Include explicit insufficient-evidence cases where abstaining is the correct behavior.
- Use synthetic examples to augment coverage, with independent human-reviewed evaluation. A teacher can label confidently wrong examples; do not use teacher agreement as ground truth.
- Keep raw private feeds/databases outside source control. Do not upload local SMS or email to a teacher service; use synthetic/sanitized approved training inputs or run the teacher locally.
- Store personal corrections in the current app profile. Retain inspectable rules/example retrieval for personalization; continual neural fine-tuning on the phone is unnecessary for the first version.

## Prototype gates

The following are proposed engineering targets to test, not observed performance or product commitments:

| Dimension | First classifier experiment |
| --- | --- |
| Incremental installed model/tokenizer/runtime budget | Aim for at most 60 MB; measure ABI-specific release delivery separately |
| Inference memory | Aim for at most 150 MB additional peak PSS above a comparable warmed app baseline; also report whole-app peak |
| Warm single-input latency | Aim for p95 below 200 ms on an agreed 4 GB RAM budget phone, with bounded short inputs |
| Cold initialization | Aim for below 2 seconds, off the UI thread |
| Suggestion quality | Explore thresholds targeting at least 98% precision on suggested cases; report confidence intervals, per-category counts, and abstention |
| Useful coverage | Improve unresolved-case coverage over rules at comparable precision; predeclare a minimum useful gain after baseline measurement |
| Offline behavior | Clean installation with all artifacts supplied; first launch and first inference in airplane mode; no hidden download |
| Resilience | Correct behavior under memory pressure, cancellation, repeated process starts, missing/corrupt model, and model upgrades |
| Accounting | No changes to totals, source fields, import eligibility, or correction precedence |

Use at least a budget 4 GB phone, a 6–8 GB midrange phone, and a recent flagship, plus an API 26 test device/emulator for supported runtime paths. Benchmark a release build, cold and warm runs, and an import-sized batch. Report thermal state, input lengths, runtime/backend, installed sizes, energy measurements where available, and accuracy after conversion/quantization. Do not generalize flagship throughput to the supported Android population.

## Effort and decision

Planning estimate for one Android/ML engineer: roughly 1–2 weeks for a tightly scoped prototype comparison if a suitable reviewed dataset and test phones already exist; roughly 4–8 or more weeks for data expansion, tuning, calibration, Android hardening, device evaluation, and release preparation. Dataset acquisition, teacher/student training, or custom conversion work can dominate that schedule. These are estimates, not commitments.

There is no per-inference server bill for a bundled model, but there are training, labeling, engineering, testing, larger distribution, battery, and maintenance costs. Review the exact model/runtime licenses and redistribution notices for the chosen artifacts. SmolLM2's card lists Apache 2.0, MiniLM's card lists MIT, and Gemma uses its own terms; these are not interchangeable licenses. [SmolLM2 card](https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct), [MiniLM card](https://huggingface.co/microsoft/MiniLM-L12-H384-uncased), [Gemma terms](https://ai.google.dev/gemma/terms).

Proceed with an offline benchmark of the current rules, a character n-gram classifier, and a task-distilled, structurally pruned INT8 encoder through the existing suggestion interface. Choose the smallest exported model that adds useful coverage at the required precision on target phones. Defer structured extraction and generation to separately evaluated tasks.

## Execution platform and hardware

The connected development machine reports Apple M2, eight CPU cores, and 8 GiB unified memory. This is enough to start data preparation and small experiments, but leaves limited headroom for model training alongside Android Studio. Apple GPU acceleration uses PyTorch's MPS backend, not CUDA; test operation support and memory use for the actual training script. [PyTorch MPS](https://docs.pytorch.org/docs/stable/notes/mps.html).

For the first experiment, use a task-fine-tuned encoder teacher, roughly BERT-base scale, and a 20–35M parameter encoder student. A multi-billion-parameter generative teacher is not necessary to attempt category distillation. Both teacher and student must predict the same app-owned label vocabulary. The teacher must demonstrate useful held-out quality before its outputs become supervision. A pretrained checkpoint without task training is not already a reliable category teacher.

These are practical planning configurations for short text, approximately 128-token inputs, small batches, and cached teacher outputs, not vendor minimums or measured requirements:

| Work | Practical configuration |
| --- | --- |
| Dataset preparation, rules baseline, export, Android integration | Existing M2/8 GiB machine; avoid running every tool concurrently |
| Local compact encoder training | Prefer 16–32 GB system/unified RAM; CPU is possible but slower; MPS is an option on supported Apple hardware |
| Repeated training and pruning recovery | One NVIDIA GPU with about 8–16 GB VRAM, ideally 16 GB, with 16–32 GB host RAM |
| Training workspace storage | Budget roughly 30–50 GB free SSD space for environments, model checkpoints, caches, and experiment artifacts |
| On-device validation | Start with an arm64 Android phone with 4 GB RAM, plus a midrange 6–8 GB phone; validate selected runtime compatibility across the app's API 26+ support range |

Memory requirements depend on batch size, sequence length, optimizer state, activations, and whether the teacher runs concurrently. Cache teacher probabilities/logits in a separate inference pass to avoid keeping teacher and student resident together. Training memory exceeds inference weight storage. [Training memory anatomy](https://huggingface.co/docs/transformers/v4.57.1/en/model_memory_anatomy).

Use a Python environment with PyTorch, Hugging Face Transformers/Datasets/tokenizers, and evaluation tooling. Write a task-specific distillation loss combining reviewed-label supervision and matching the teacher's category distribution. PyTorch's tutorial illustrates the teacher/student loss pattern; its example is vision, so it must be adapted to this text task. [Knowledge distillation tutorial](https://docs.pytorch.org/tutorials/beginner/knowledge_distillation_tutorial.html).

For pruning, use architecture-aware PyTorch code that removes supported layers or feed-forward units and updates model configuration and dependent tensors. `torch.nn.utils.prune` is useful for mask experiments, but making a mask permanent alone does not physically compact tensor dimensions. Verify parameter counts, serialized size, export correctness, and phone speed after compaction. [PyTorch pruning tutorial](https://docs.pytorch.org/tutorials/intermediate/pruning_tutorial.html).

Export a tiny compatibility prototype to ONNX early, before committing to an architecture. After distillation, pruning, and recovery, export the final float model and try ONNX Runtime dynamic INT8 quantization as the initial transformer CPU path. Measure accuracy after conversion and run with the intended Android runtime; use a different quantization path or quantization-aware recovery if quality or operator support requires it. [ONNX quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html).

Use Android Studio, the existing Kotlin/Gradle app, a bundled ONNX Runtime Android dependency, and a tokenizer implementation verified against the training tokenizer. Package model, vocabulary, labels, and version metadata as app assets. Android does not need Python or the teacher. Validate first inference in airplane mode and measure release-build performance on real phones. [ONNX mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/).

A hosted GPU notebook such as Colab is optional development compute, not a production dependency. Its GPU availability and limits vary. Only synthetic or appropriately sanitized approved data should go to hosted compute under the current local-data contract; use local training for private raw messages. No cloud GPU has been provisioned. [Colab FAQ](https://research.google.com/colaboratory/faq.html).

Retain comparable checkpoints and reports for the deterministic baseline, teacher, distilled student, pruned/recovered student, and final quantized Android artifact. Choose pruning depth and confidence thresholds using validation data, then report once on an untouched test set. No training duration, final artifact size, or target-phone performance has been established yet.

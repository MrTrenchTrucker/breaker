# ADR-050: Two speech models, the user chooses between them; each model pinned by our own sha256

**Status:** accepted (the first build change is built, not compiled or run; the second is not built: see Build status below)
**Date:** 2026-11-18
**Approved by:** project owner (typed approval, 2026-11-18)

Read with ADR-003 and ADR-013 (the pin rule, amended here), ADR-016 (the registry), ADR-025 (where the model comes from) and ADR-028 (the model survives an update).

## Context

The app has one speech model today, `small` (Apache-2.0, 365,748,162 bytes, 348.8 MiB). The first-run model step offers only that one. The settings screen shows the model id as text. It can only download that one model when it is missing. It has no way to delete or switch models, and no list of the models on the phone. The settings Download control is reused per model.

NVIDIA's Parakeet TDT 0.6B v3 may transcribe better. It is an offline model: it decodes a whole take once, after the user stops. Its sherpa-onnx int8 archive is 487,211,443 bytes (464.6 MiB), about 1.33 times the size of `small`. Upstream publishes no checksum.txt line for it, so the app cannot check it against upstream's list the way it checks `small` today. The model is licensed under CC BY 4.0, which needs attribution.

The project owner decided that the user chooses. Typed on 2026-11-18, verbatim:

> "first time launch of the app where you would download the other model, there should be a second option right under it to download Parakeet instead. For first VTT model should say: good. Parakeet should say: better. Eventually I would want like a star system to rate how good one model is over another but for now we can use truck emoji's. The first VTT model gets rated 1 of 3 trucks to indicate how good it is, Parakeet gets 2 of 3 trucks. The settings menu also needs the ability to download either or delete either, and to switch from one to another."

Licence conditions for Parakeet, from its CC BY 4.0 terms and the owner's instruction:

1. Attribution wherever the model is distributed, including downloads and sideloads. That means an entry in the About screen and a NOTICE file in the repository.
2. No terms and no technical measures on the model files that restrict the CC BY 4.0 rights. Any future Breaker terms of use exclude third-party components.
3. No implied endorsement by NVIDIA and no NVIDIA logos. The factual name "Parakeet" is fine.

## Decision

Everything below is the target. The first build change has put A, E and G and the registry side of B into the code (not compiled or run); the screens in B, C and D and the notices in H belong to the second build change and are not built.

A. **The second model.** The second model is Parakeet TDT 0.6B v3, packaged by the sherpa-onnx project as the release asset `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (487,211,443 bytes). Its registry id is `parakeet`. The existing id `small` does not change.

B. **Labels and ratings are data, fixed for now.** `small` is labelled "Good" and rated 1 of 3 truck emoji. `parakeet` is labelled "Better" and rated 2 of 3 truck emoji. The label and the rating live in the model registry (ADR-016), so the first-run step and the settings screen read one source. A screen reader reads "Good, 1 of 3" and "Better, 2 of 3". A star system is out of scope for now.

C. **First run.** The existing download button stays first. Directly under it is a second button that downloads Parakeet instead. Each button shows its label, its rating and its download size, taken from the registry. Exactly one model is downloaded at first run, and the one downloaded becomes the selected model.

D. **Settings.** A speech model section lists both models. For each model the section offers:
   - Download, when the model is not installed;
   - Delete, when the model is installed and is not the one in use;
   - Use this one, when the model is installed and is not the one in use.

   The model in use cannot be deleted. The user switches first. The choice is kept in the existing setting `AppSettings.modelSize`, and the existing values stay valid. Switching does not delete the other model. An install touches only its own model id.

E. **Parakeet is offline.** A take is decoded once, after stop. Today's take path already works this way (`OnDeviceSttEngine`), so the take shape does not change. The stt-ondevice module gains:
   - an offline recognizer behind its existing recognizer interface;
   - a kind switch, chosen by the registry entry;
   - an extraction profile whose limits come from the real archive listing. The `small` limits do not fit this archive: its 448 MiB stream limit is below 464.6 MiB.

   Download limits follow the registry size.

F. **Wake and send phrases are unaffected.** They run on the separate keyword model (training option C). No phrase code reads the speech-model setting.

G. **Integrity pin.** Every model is pinned by our own sha256, byte size and release-asset id, held in the registry. The app verifies each download against that pin. The upstream checksum.txt becomes advisory, not required. This applies to every model. Parakeet has no upstream checksum.txt line, and an upstream re-upload broke installs on 2026-11-18. ADR-003 and ADR-013 say the pin must match upstream's list. They are amended in this same change.

H. **Notices.** The About screen gets a third-party notices entry with the attribution text below. The NOTICE file at the repository root carries the same text. ADR-025 gets a Parakeet line. The attribution text, exactly:

   > This app includes the Parakeet TDT 0.6B v3 model by NVIDIA Corporation (https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Model weights were converted/quantized from the original.

I. **Not decided here.** These are left open:
   - per-user training (option A) and any phone-versus-server calibration;
   - the bench numbers (word error rate, speed, memory). They are pending and do not block this decision;
   - a real-phone memory check, which is an acceptance item for the build.

**Build split.** Two build changes, in this order:

1. `android/modules/stt-ondevice` and the shared model-registry entry: the offline recognizer, the extraction profile and the pin.
2. `android/ui` and the settings module: the first-run second button, the settings section, the ratings, and the notices.

**Build status.** The first build change (`android/modules/stt-ondevice` and the registry) is built, not compiled or run: the registry holds the `parakeet` entry and, for every entry, its own byte size, asset id, kind, label, rating and offered flag; the speech module installs and loads each model against that pin (upstream's checksum.txt is no longer fetched), has an unpack profile and an offline recogniser for `parakeet`, and chooses the recogniser by model id. Not built: the offline native binding has never been compiled against the sherpa-onnx library, so its library names are unverified; the real-phone memory check is pending; the first-run second button, the settings section, the ratings display, the About notice entry and the NOTICE text belong to the second build change (the `android/ui` and settings modules). Nothing downloads a model from a screen yet, and the app still offers `small` only.

## Reasons

- The user chooses between a better model and a larger download. The owner asked for that choice.
- The owner rates Parakeet 2 of 3 trucks and `small` 1 of 3. The labels show that rating.
- Our own pin is the only check that works for both models, because Parakeet has no upstream line.
- Labels and ratings in the registry give both screens one source (ADR-016).
- Each install touches only its own folder, so switching never loses the other model.
- Each button shows its download size, so the user can weigh the larger Parakeet download (about 1.33 times the archive) before choosing.

## Consequences

Rules in: each registry entry carries its own pin, size, label and rating. The first-run step and the settings section read the registry. The notices name each model. An install touches only its own id. The upstream checksum.txt is advisory. The model in use is never deleted.

Rules out: a model that is not in the registry; deleting the model in use; a rule that an upstream checksum line must exist for a model to install; any new speech model that ships without its own pin and its own notice text; a star rating in place of trucks until the owner asks for it.

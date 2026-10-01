"""The shared stray-file rule: a contract or registry file outside its own
module folder is a repo error.

`find_contract_strays` is the rule's walker. The shared module's contract
test applies it to the real tree (`tests/contract/test_shared_contract.py`)
and the unit tests apply it to planted temp trees (`tests/unit/contract/
test_card_section_repeats_and_strays.py`). It lives in its own support file
— no `test_` prefix, exactly like contract_support.py — so the
contract-file bijection never sees it.
"""
import os


def find_contract_strays(root):
    """Name every contract or registry file that lives outside its own module
    folder: an OpenAPI spec (`openapi*.y*ml`) outside
    `shared/modules/api-contracts/`, or a model registry data file
    (`models.yaml`) outside `shared/modules/model-registry/`.

    The walk excludes .git, build/, .gradle/ and .kotlin/ (version control and
    build state, never repo source). Returns a list of human-readable offender
    strings, one per stray; an empty list means the tree is clean.
    """
    offenders = []
    for cur, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs
                   if d not in (".git", "build", ".gradle", ".kotlin")]
        for name in files:
            rel = os.path.relpath(os.path.join(cur, name), root)
            if name.startswith("openapi") and (
                name.endswith(".yml") or name.endswith(".yaml")
            ):
                if not rel.startswith("shared/modules/api-contracts/"):
                    offenders.append(
                        f"{rel} — an OpenAPI spec belongs in "
                        f"shared/modules/api-contracts"
                    )
            elif name == "models.yaml":
                if not rel.startswith("shared/modules/model-registry/"):
                    offenders.append(
                        f"{rel} — the model registry data file belongs in "
                        f"shared/modules/model-registry"
                    )
    return offenders

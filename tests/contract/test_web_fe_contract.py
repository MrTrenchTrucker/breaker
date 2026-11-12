"""Contract test for the `server/modules/web-fe` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class ServerModulesWebFeContractTest(contract_support.ModuleContractTest):
    MODULE = "server_web_fe"


from web_fe_aesgcm_pins import WebFeAesGcmPinsTest  # noqa: F401  (AES-GCM layer pins, collected here)
from web_fe_signin_pins import WebFeSigninPinsTest  # noqa: F401  (sign-in page pins, collected here)
from web_fe_signin_page_pins import WebFeSigninPagePinsTest  # noqa: F401  (sign-in page shell pins, collected here)
from web_fe_signin_kdf_pins import WebFeSigninKdfPinsTest  # noqa: F401  (key derivation wrapper pins, collected here)
from web_fe_signin_box_pins import WebFeSigninBoxPinsTest  # noqa: F401  (box public key pins, collected here)
from web_fe_box_aad_pins import WebFeBoxAadPinsTest  # noqa: F401  (box purpose AAD pins, collected here)
from web_fe_signin_seal_pins import WebFeSigninSealPinsTest  # noqa: F401  (signin seal source pins, collected here)


if __name__ == "__main__":
    unittest.main()

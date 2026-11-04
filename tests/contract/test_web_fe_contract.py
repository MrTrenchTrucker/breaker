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


if __name__ == "__main__":
    unittest.main()

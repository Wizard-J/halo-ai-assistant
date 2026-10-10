import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
from urllib.parse import urlencode
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location("chatgpt_login", Path(__file__).with_name("chatgpt-login.py"))
login = importlib.util.module_from_spec(spec)
spec.loader.exec_module(login)


class LoginTest(unittest.TestCase):
    def test_callback_requires_matching_state_and_issued_client(self):
        query = urlencode(dict(state="state", code="code", client_id="oaiapp_one"))
        self.assertEqual(("code", "oaiapp_one"), login.validate_callback(query, "state", "dynamic_agent_client"))
        self.assertEqual(("code", "oaiapp_one"), login.validate_callback(query, "state", "oaiapp_one"))
        with self.assertRaises(ValueError):
            login.validate_callback(query, "wrong-state", "dynamic_agent_client")
        with self.assertRaises(ValueError):
            login.validate_callback(query, "state", "oaiapp_other")
        with self.assertRaises(ValueError):
            login.validate_callback("state=state&code=code", "state", "dynamic_agent_client")
        with self.assertRaises(ValueError):
            login.validate_callback("state=state&error=access_denied", "state", "oaiapp_one")
        self.assertEqual(("code", "oaiapp_one"),
                         login.validate_callback("state=state&code=code", "state", "oaiapp_one"))

    def test_credentials_written_atomically_with_private_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "profile" / "credentials.json"
            login.write_private(path, {"client_id": "test"})
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
            self.assertEqual({"client_id": "test"}, login.read_private(path))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                login.read_private(path)

    def test_identity_requires_signature_nonce_audience_and_same_subject(self):
        discovery = {"jwks_uri": "https://auth.openai.com/.well-known/jwks.json"}
        claims = {"nonce": "nonce", "sub": "same-user"}
        with patch("jwt.PyJWKClient") as keys, patch("jwt.decode", return_value=claims) as decode:
            keys.return_value.get_signing_key_from_jwt.return_value.key = "public-key"
            self.assertEqual(claims, login.validate_identity("token", "client", "nonce", discovery, "same-user"))
            self.assertEqual("client", decode.call_args.kwargs["audience"])
            self.assertEqual(login.ISSUER, decode.call_args.kwargs["issuer"])
            self.assertIn("exp", decode.call_args.kwargs["options"]["require"])
            with self.assertRaises(ValueError):
                login.validate_identity("token", "client", "wrong-nonce", discovery)
            with self.assertRaises(ValueError):
                login.validate_identity("token", "client", "nonce", discovery, "different-user")
        with self.assertRaises(ValueError):
            login.validate_identity("token", "client", "nonce", {"jwks_uri": "https://example.com/jwks"})


if __name__ == "__main__":
    unittest.main()

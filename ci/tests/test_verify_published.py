from contextlib import ExitStack, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


spec = importlib.util.spec_from_file_location(
    "verify_published", Path(__file__).resolve().parents[1] / "verify-published.py")
published = importlib.util.module_from_spec(spec)
spec.loader.exec_module(published)


class VerifyPublishedTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.local = Path(directory.name) / "artifact"
        self.local.write_bytes(b"release bytes")
        self.names = [f"scalacv_3-0.4.4{suffix}" for suffix in
                      (".pom", ".jar", "-sources.jar", "-javadoc.jar")]
        self.manifest = json.dumps({
            "meta": {"group": "com.worxbend", "id": "scalacv_3", "version": "0.4.4"},
            "payload": [[f"ref:v0:hash:{self.local}", name] for name in self.names],
        })

    def response(self, body=b"release bytes"):
        return io.BytesIO(body)

    def http_error(self, code):
        return HTTPError("https://repo.example/file", code, "test response", {}, None)

    def verify(self, responses, times=(0,), repository=published.CENTRAL):
        with ExitStack() as stack:
            stack.enter_context(patch.dict(os.environ, {
                "GITHUB_REF_NAME": "v0.4.4", "GITHUB_REF_TYPE": "tag",
                "GITHUB_PACKAGES_USERNAME": "test", "GITHUB_PACKAGES_PASSWORD": "test",
            }))
            stack.enter_context(patch("sys.argv", ["verify-published.py", repository]))
            stack.enter_context(patch.object(published, "ARTIFACTS", {"core": "scalacv_3"}))
            stack.enter_context(patch.object(published.subprocess, "check_output", return_value=self.manifest))
            self.open = stack.enter_context(patch.object(published.urllib.request, "urlopen", side_effect=responses))
            self.clock = stack.enter_context(patch.object(published.time, "monotonic", side_effect=times))
            self.sleep = stack.enter_context(patch.object(published.time, "sleep"))
            self.output = io.StringIO()
            stack.enter_context(redirect_stdout(self.output))
            published.main()

    def test_matches_all_bytes_closes_responses_and_reports_each_file(self):
        responses = [self.response() for _ in self.names]
        self.verify(responses)
        self.assertTrue(all(response.closed for response in responses))
        self.assertEqual(4, self.open.call_count)
        for name, call in zip(self.names, self.open.call_args_list):
            request = call.args[0]
            self.assertEqual(f"{published.CENTRAL}/com/worxbend/scalacv_3/0.4.4/{name}", request.full_url)
            self.assertEqual({}, request.headers)
            self.assertEqual({"timeout": 30}, call.kwargs)
        self.assertEqual(4, len(self.output.getvalue().splitlines()))
        self.sleep.assert_not_called()

    def test_rejects_mismatched_bytes_before_reporting_success(self):
        response = self.response(b"different")
        with self.assertRaisesRegex(ValueError, "remote release file differs.*scalacv_3-0.4.4.pom"):
            self.verify([response])
        self.assertTrue(response.closed)
        self.assertEqual("", self.output.getvalue())
        self.assertEqual(1, self.open.call_count)

    def test_retries_each_transient_http_status_then_verifies(self):
        for code in (404, 429, 500, 502, 503, 504):
            with self.subTest(code=code):
                self.verify([self.http_error(code)] + [self.response() for _ in self.names], times=(0, 1))
                self.sleep.assert_called_once_with(5)
                self.assertEqual(5, self.open.call_count)

    def test_permanent_http_error_propagates_without_sleep(self):
        error = self.http_error(401)
        with self.assertRaises(HTTPError) as caught:
            self.verify([error])
        self.assertIs(error, caught.exception)
        self.sleep.assert_not_called()

    def test_transport_failure_retries_then_verifies(self):
        self.verify([URLError("unavailable")] + [self.response() for _ in self.names], times=(0, 1))
        self.sleep.assert_called_once_with(5)

    def test_deadline_exhaustion_propagates_http_and_transport_errors(self):
        for error in (self.http_error(503), URLError("unavailable")):
            with self.subTest(error=error), self.assertRaises(type(error)) as caught:
                self.verify([error], times=(10, 910))
            self.assertIs(error, caught.exception)
            self.sleep.assert_not_called()

    def test_propagation_deadline_is_shared_across_files(self):
        error = self.http_error(404)
        with self.assertRaises(HTTPError) as caught:
            self.verify([self.http_error(404), self.response(), error], times=(0, 899, 901))
        self.assertIs(error, caught.exception)
        self.assertEqual(3, self.open.call_count)
        self.sleep.assert_called_once_with(5)
        self.assertEqual(1, len(self.output.getvalue().splitlines()))

    def test_github_uses_the_shorter_deadline_and_authorized_request(self):
        with self.assertRaises(HTTPError):
            self.verify([self.http_error(404)], times=(0, 120),
                        repository="https://maven.pkg.github.com/worxbend/scalacv/")
        request = self.open.call_args.args[0]
        self.assertTrue(request.headers["Authorization"].startswith("Basic "))
        self.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()

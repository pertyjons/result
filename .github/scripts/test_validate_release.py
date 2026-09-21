import unittest
from validate_release import validate


class ReleaseValidationTest(unittest.TestCase):
    def test_matching_tag(self):
        self.assertEqual(validate("group=x\nversion=1.2.3\n", "v1.2.3", "tag"), "1.2.3")

    def test_prerelease_tag(self):
        self.assertEqual(validate("version=1.2.3-rc.1", "v1.2.3-rc.1", "tag"), "1.2.3-rc.1")

    def test_properties_spacing_and_comments(self):
        self.assertEqual(validate("# version=old\n version = 1.2.3 \n", "v1.2.3", "tag"), "1.2.3")

    def test_rejects_mismatch(self):
        with self.assertRaisesRegex(ValueError, "must be v1.2.3"):
            validate("version=1.2.3", "v1.2.4", "tag")

    def test_rejects_snapshot(self):
        for version in ["1.2.3-SNAPSHOT", "1.2.3-snapshot"]:
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "SNAPSHOT"):
                validate(f"version={version}", f"v{version}", "tag")

    def test_rejects_branch_dispatch_even_if_name_matches(self):
        with self.assertRaisesRegex(ValueError, "requires a tag"):
            validate("version=1.2.3", "v1.2.3", "branch")

    def test_rejects_missing_and_duplicate_version(self):
        for properties in ["", "version=", "version=1.2.3\nversion=1.2.3"]:
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                validate(properties, "v1.2.3", "tag")

    def test_rejects_malformed_version(self):
        with self.assertRaises(ValueError):
            validate("version=latest", "vlatest", "tag")

    def test_rejects_missing_or_unprefixed_tag(self):
        for tag in ["", "1.2.3"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate("version=1.2.3", tag, "tag")


if __name__ == "__main__":
    unittest.main()

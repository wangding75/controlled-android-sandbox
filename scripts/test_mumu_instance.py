#!/usr/bin/env python3
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import mumu_instance as resolver


class MuMuInstanceResolverTest(unittest.TestCase):
    def test_discovery_uses_exact_instance_name_and_vm_identity(self) -> None:
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            config = root / "vms" / "MuMuPlayer-12.0-0" / "configs"
            config.mkdir(parents=True)
            (config / "extra_config.json").write_text(
                json.dumps({"playerName": "RD测试"}, ensure_ascii=False), encoding="utf-8"
            )
            (config / "vm_config.json").write_text(
                json.dumps(
                    {
                        "vm": {
                            "ginstance": "ginstance-test",
                            "nat": {"port_forward": {"adb": {"host_port": "19001"}}},
                            "phone": {"manufacturer": "Samsung", "miit": "SM-A5260"},
                        }
                    }
                ),
                encoding="utf-8",
            )
            instances = resolver.discover_instances(root)
        self.assertEqual(["RD测试"], [item["name"] for item in instances])
        self.assertEqual("ginstance-test", instances[0]["id"])
        self.assertEqual("127.0.0.1:19001", instances[0]["configuredSerial"])

    def test_project_scope_is_explicit(self) -> None:
        self.assertEqual("Controlled Android Sandbox / 闪现2", resolver.PROJECT_SCOPE)
        self.assertEqual("RD测试", resolver.DEFAULT_INSTANCE_NAME)


if __name__ == "__main__":
    unittest.main()

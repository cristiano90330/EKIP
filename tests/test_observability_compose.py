"""检查 Compose 可选观测服务的变量转交、依赖关系及隔离配置。"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docker-compose.yml"
OVERLAY = ROOT / "docker-compose.observability.yml"
FIXTURE = ROOT / "tests/fixtures/observability-compose.env"
CORE = {"postgres", "redis", "minio", "minio-init", "ai", "backend"}
EXTERNAL = {"otel-collector", "clickhouse", "langfuse-web", "langfuse-worker"}


def compose_config(overlay, unconfigured_langfuse=False, missing_key=None):
    """向 `models` 提供预设数据。返回 `subprocess.run` 的结果。

    输入为 `overlay`、`unconfigured_langfuse`、`missing_key`。
    上游为 `test_default_needs_no_langfuse_keys_but_explicit_overlay_requires_them`、`test_each_required_observability_secret_fails_fast_when_missing`。
    """
    if not shutil.which("docker"):
        pytest.skip("Docker Compose CLI is required for configuration validation")
    env = os.environ.copy()
    keys = set(re.findall(r"\$\{([A-Z_0-9]+)", BASE.read_text(encoding="utf-8") + OVERLAY.read_text(encoding="utf-8")))
    for key in keys | {"COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_ENV_FILES", "COMPOSE_PROJECT_NAME"}:
        env.pop(key, None)
    if unconfigured_langfuse:
        # Process values override the fixture, without creating temporary dotenv files.
        for key in keys:
            if key.startswith("LANGFUSE_"):
                env[key] = ""
    if missing_key:
        env[missing_key] = ""
    command = ["docker", "compose", "--env-file", str(FIXTURE), "-p", "ekip-observability-contract", "-f", str(BASE)]
    if overlay:
        command += ["-f", str(OVERLAY)]
    command += ["config", "--format", "json"]
    return subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", timeout=30)


@pytest.fixture(scope="module")
def models():
    """为需要该夹具的用例准备输入和隔离状态。返回 `result`。下游调用 `compose_config`。"""
    result = {}
    for overlay in (False, True):
        parsed = compose_config(overlay)
        assert parsed.returncode == 0, "Compose configuration validation failed; full configuration output suppressed"
        result[overlay] = json.loads(parsed.stdout)
    return result


def buckets(model):
    """向 `test_metrics_logs_and_business_storage_remain_in_default_model` 提供预设数据。返回 `set` 类型的值。

    输入为 `model`。
    上游为 `test_optional_bucket_and_existing_volume_names_are_preserved`。
    """
    entrypoint = str(model["services"]["minio-init"]["entrypoint"])
    return set(re.findall(r"local/([a-zA-Z0-9_-]+)", entrypoint))


def test_default_services_have_no_external_tracing_dependency(models):
    """验证 `set(services)` 等于 `CORE`，且 `'otel-collector'` 不属于 `services['ai']['depends_on']`。"""
    services = models[False]["services"]
    assert set(services) == CORE
    assert "otel-collector" not in services["ai"]["depends_on"]
    assert services["ai"]["environment"]["OTEL_ENABLED"] == "false"
    assert services["backend"]["environment"]["OTEL_ENABLED"] == "false"


def test_metrics_logs_and_business_storage_remain_in_default_model(models):
    """验证 `env['METRICS_ENABLED']` 等于 `'true'`，且 `env['RAG_STAGE_LOG_ENABLED']` 等于 `'true'`。"""
    model = models[False]
    env = model["services"]["ai"]["environment"]
    assert env["METRICS_ENABLED"] == "true"
    assert env["RAG_STAGE_LOG_ENABLED"] == "true"
    assert set(model["volumes"]) == {"pg_data", "minio_data"}
    assert buckets(model) == {"knowledge-files"}


def test_explicit_overlay_adds_tracing_and_preserves_core_dependencies(models):
    """验证 `set(services)` 等于 `CORE | EXTERNAL`，且 `set(services['ai']['depends_on'])` 等于 `{'minio', 'postgres', 'otel-collector'}`。"""
    services = models[True]["services"]
    assert set(services) == CORE | EXTERNAL
    assert set(services["ai"]["depends_on"]) == {"minio", "postgres", "otel-collector"}
    assert services["ai"]["environment"]["OTEL_ENABLED"] == "true"
    assert services["backend"]["environment"]["OTEL_ENABLED"] == "true"
    assert services["ai"]["environment"]["OTEL_EXPORTER_OTLP_ENDPOINT"] == "http://otel-collector:4318/v1/traces"
    assert services["backend"]["environment"]["OTEL_TRACES_ENDPOINT"] == "http://otel-collector:4318/v1/traces"
    assert "LANGFUSE_BASIC_AUTH" in services["otel-collector"]["environment"]
    for service in ("langfuse-web", "langfuse-worker"):
        assert services[service]["depends_on"]["minio-init"]["condition"] == "service_completed_successfully"


def test_optional_bucket_and_existing_volume_names_are_preserved(models):
    """验证 `buckets(model)` 等于 `{'knowledge-files', 'langfuse'}`，且 `model['services'][service].get(key)` 等于 `models[False]['services'][service].get(key)`。"""
    model = models[True]
    assert buckets(model) == {"knowledge-files", "langfuse"}
    assert set(model["volumes"]) == {"pg_data", "minio_data", "clickhouse_data", "clickhouse_logs"}
    for service in ("postgres", "redis", "minio"):
        # Compare only safe structural fields; never include credential maps in assertion output.
        for key in ("image", "ports", "volumes", "healthcheck"):
            assert model["services"][service].get(key) == models[False]["services"][service].get(key)


def test_default_needs_no_langfuse_keys_but_explicit_overlay_requires_them():
    """验证 `compose_config(False, unconfigured_langfuse=True).returncode == 0` 成立，且 `optional.returncode` 不等于 `0`。"""
    assert compose_config(False, unconfigured_langfuse=True).returncode == 0
    optional = compose_config(True, unconfigured_langfuse=True)
    assert optional.returncode != 0
    assert "LANGFUSE_" in optional.stderr


@pytest.mark.parametrize("key", ["LANGFUSE_BASIC_AUTH", "LANGFUSE_NEXTAUTH_SECRET",
                                 "LANGFUSE_SALT", "LANGFUSE_ENCRYPTION_KEY"])
def test_each_required_observability_secret_fails_fast_when_missing(key):
    """验证 `optional.returncode` 不等于 `0`，且 `key` 属于 `optional.stderr`。"""
    optional = compose_config(True, missing_key=key)
    assert optional.returncode != 0
    assert key in optional.stderr

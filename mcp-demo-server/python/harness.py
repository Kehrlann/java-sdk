"""Runs the Python MCP client against mcp-demo-server, over stdio, and reports each test.

Usage (from this directory):

    uv run harness.py              # (re)build the server jar if stale, run all tests
    uv run harness.py -k book      # only run tests whose name contains "book"
    uv run harness.py --no-build   # never build, use the existing jar

Each test launches its own server process. The server's stderr goes to
`mcp-demo-server/target/python-harness/server.log`.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import traceback
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TextIO

import anyio
import mcp_types as types
from mcp import Client
from mcp.client.session import ClientRequestContext
from mcp.client.stdio import StdioServerParameters, stdio_client
from mcp.shared.exceptions import MCPError

PROTOCOL_VERSION = "2026-07-28"

HERE = Path(__file__).resolve().parent
MODULE_DIR = HERE.parent
REPO_ROOT = MODULE_DIR.parent
JAR = MODULE_DIR / "target" / "mcp-demo-server-2.1.0-SNAPSHOT.jar"
SERVER_LOG = MODULE_DIR / "target" / "python-harness" / "server.log"

# Sources that end up in the shaded jar: a change in any of them makes the jar stale.
JAR_SOURCES = [
    MODULE_DIR / "pom.xml",
    MODULE_DIR / "src",
    REPO_ROOT / "mcp-core-modern" / "src" / "main",
    REPO_ROOT / "mcp-json" / "src" / "main",
    REPO_ROOT / "mcp-json-jackson3" / "src" / "main",
]

TEST_TIMEOUT_SECONDS = 30

CLIENT_INFO = types.Implementation(name="mcp-demo-server-python-harness", version="0.1.0")

# ---------------------------
# Harness
# ---------------------------

ElicitationHandler = Callable[[types.ElicitRequestParams], types.ElicitResult]


@dataclass
class Test:
    name: str
    fn: Callable[[], Awaitable[None]]


TESTS: list[Test] = []


def test(fn: Callable[[], Awaitable[None]]) -> Callable[[], Awaitable[None]]:
    TESTS.append(Test(fn.__name__.removeprefix("test_"), fn))
    return fn


_server_log: TextIO = sys.stderr


@asynccontextmanager
async def connect(elicit: ElicitationHandler | None = None, **kwargs: Any):
    """Launches the demo server and connects a client to it, pinned to the 2026-07-28 protocol.

    The client's response cache is disabled, so that every call reaches the server.
    """

    async def elicitation_callback(
        context: ClientRequestContext, params: types.ElicitRequestParams
    ) -> types.ElicitResult:
        if elicit is None:
            raise AssertionError(f"unexpected elicitation: {params.message}")
        return elicit(params)

    params = StdioServerParameters(command="java", args=["-jar", str(JAR)], env={"MCP_MODE": "stdio"})
    options: dict[str, Any] = {
        "mode": PROTOCOL_VERSION,
        "client_info": CLIENT_INFO,
        "elicitation_callback": elicitation_callback,
        "cache": None,
    }
    async with Client(stdio_client(params, errlog=_server_log), **(options | kwargs)) as client:
        yield client


def text_of(result: types.CallToolResult) -> str:
    assert len(result.content) == 1, f"expected a single content block, got {result.content}"
    content = result.content[0]
    assert isinstance(content, types.TextContent), f"expected text content, got {content}"
    return content.text


def assert_equal(actual: Any, expected: Any) -> None:
    assert actual == expected, f"expected {expected!r}, got {actual!r}"


async def assert_mcp_error(call: Awaitable[Any], code: int, message: str | None = None) -> MCPError:
    try:
        result = await call
    except MCPError as error:
        assert_equal(error.error.code, code)
        if message is not None:
            assert_equal(error.error.message, message)
        return error
    raise AssertionError(f"expected an MCPError with code {code}, got {result}")


def accept(content: dict[str, Any]) -> types.ElicitResult:
    return types.ElicitResult(action="accept", content=content)


# ---------------------------
# Discovery
# ---------------------------


@test
async def test_discover() -> None:
    async with connect(mode="auto") as client:
        assert_equal(client.protocol_version, PROTOCOL_VERSION)
        assert client.server_info is not None
        assert_equal(client.server_info.name, "mcp-demo-server")
        assert_equal(client.server_info.version, "0.1.0")
        assert client.instructions is not None and "book_table" in client.instructions
        capabilities = client.server_capabilities
        assert capabilities.tools is not None, "missing tools capability"
        assert capabilities.resources is not None, "missing resources capability"
        assert capabilities.prompts is not None, "missing prompts capability"
        assert capabilities.completions is not None, "missing completions capability"


@test
async def test_server_info_in_result_meta() -> None:
    async with connect() as client:
        result = await client.call_tool("echo", {"message": "hi"})
        assert result.meta is not None
        assert_equal(result.meta.get("io.modelcontextprotocol/serverInfo"), {"name": "mcp-demo-server", "version": "0.1.0"})


# ---------------------------
# Tools
# ---------------------------


@test
async def test_list_tools() -> None:
    async with connect() as client:
        tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        assert_equal(sorted(tools), ["add", "book_table", "client_info", "countdown", "echo"])
        assert tools["echo"].annotations is not None and tools["echo"].annotations.read_only_hint
        assert tools["add"].output_schema is not None, "add has no output schema"
        assert_equal(tools["countdown"].input_schema["required"], ["seconds"])


@test
async def test_echo() -> None:
    async with connect() as client:
        result = await client.call_tool("echo", {"message": "Hello, MCP!"})
        assert not result.is_error
        assert_equal(text_of(result), "Hello, MCP!")


@test
async def test_client_info_from_request_meta() -> None:
    async with connect(log_level="info") as client:
        info = json.loads(text_of(await client.call_tool("client_info", {})))
        assert_equal(info["protocolVersion"], PROTOCOL_VERSION)
        assert_equal(info["clientInfo"], {"name": CLIENT_INFO.name, "version": CLIENT_INFO.version})
        assert "elicitation" in info["clientCapabilities"], f"no elicitation capability in {info}"
        assert_equal(info["logLevel"], "info")


@test
async def test_add_structured_content() -> None:
    async with connect() as client:
        result = await client.call_tool("add", {"a": 2, "b": 3.5})
        assert not result.is_error
        assert_equal(result.structured_content, {"sum": 5.5})
        assert_equal(text_of(result), '{"sum":5.5}')


@test
async def test_unknown_tool() -> None:
    async with connect() as client:
        await assert_mcp_error(client.call_tool("nope", {}), types.INVALID_PARAMS, "Unknown tool: nope")


@test
async def test_countdown_progress() -> None:
    progress: list[tuple[float, float | None, str | None]] = []

    async def on_progress(value: float, total: float | None, message: str | None) -> None:
        progress.append((value, total, message))

    async with connect() as client:
        result = await client.call_tool("countdown", {"seconds": 2}, progress_callback=on_progress)
        assert_equal(text_of(result), "Liftoff!")
        assert_equal(progress, [(1.0, 2.0, "1/2 seconds"), (2.0, 2.0, "2/2 seconds")])


@test
async def test_countdown_cancellation() -> None:
    progress: list[float] = []
    first_progress = anyio.Event()

    async def on_progress(value: float, total: float | None, message: str | None) -> None:
        progress.append(value)
        first_progress.set()

    async with connect() as client:
        async with anyio.create_task_group() as tg:

            async def countdown() -> None:
                await client.call_tool("countdown", {"seconds": 30}, progress_callback=on_progress)
                raise AssertionError("countdown completed, it should have been cancelled")

            tg.start_soon(countdown)
            with anyio.fail_after(5):
                await first_progress.wait()
            tg.cancel_scope.cancel()

        # The server is still usable, and stops reporting progress for the cancelled request
        assert_equal(text_of(await client.call_tool("echo", {"message": "still there"})), "still there")
        received = len(progress)
        await anyio.sleep(1.5)
        assert_equal(len(progress), received)


# ---------------------------
# Multi round-trip requests (MRTR)
# ---------------------------


def booking_user(party_size: int, slot: str, confirm: bool) -> ElicitationHandler:
    def handler(params: types.ElicitRequestParams) -> types.ElicitResult:
        if params.message.startswith("Booking a table"):
            return accept({"partySize": party_size, "time": slot})
        if params.message.startswith("Book a table"):
            return accept({"confirm": confirm})
        raise AssertionError(f"unexpected elicitation: {params.message}")

    return handler


@test
async def test_book_table_input_required() -> None:
    """The first round, without the client's MRTR driver: the server asks for the details."""
    async with connect() as client:
        result = await client.session.call_tool(
            "book_table", {"restaurant": "Chez Nous"}, allow_input_required=True
        )
        assert isinstance(result, types.InputRequiredResult), f"expected InputRequiredResult, got {result}"
        assert result.input_requests is not None
        assert_equal(list(result.input_requests), ["details"])
        request = result.input_requests["details"]
        assert isinstance(request, types.ElicitRequest), f"expected an elicitation, got {request}"
        assert_equal(request.params.message, "Booking a table at Chez Nous.")
        assert_equal(sorted(request.params.requested_schema["required"]), ["partySize", "time"])


@test
async def test_book_table_confirmed() -> None:
    elicitations: list[str] = []
    user = booking_user(4, "19:30", confirm=True)

    def handler(params: types.ElicitRequestParams) -> types.ElicitResult:
        elicitations.append(params.message)
        return user(params)

    async with connect(elicit=handler) as client:
        result = await client.call_tool("book_table", {"restaurant": "Chez Nous"})
        assert_equal(text_of(result), "Booked a table for 4 at Chez Nous, at 19:30.")
        assert_equal(elicitations, ["Booking a table at Chez Nous.", "Book a table for 4 at Chez Nous, at 19:30?"])


@test
async def test_book_table_not_confirmed() -> None:
    async with connect(elicit=booking_user(2, "18:00", confirm=False)) as client:
        result = await client.call_tool("book_table", {"restaurant": "Chez Nous"})
        assert_equal(text_of(result), "No table booked.")


@test
async def test_book_table_details_declined() -> None:
    async with connect(elicit=lambda params: types.ElicitResult(action="decline")) as client:
        result = await client.call_tool("book_table", {"restaurant": "Chez Nous"})
        assert_equal(text_of(result), "No table booked.")


@test
async def test_book_table_tampered_request_state() -> None:
    async with connect() as client:
        for request_state in ["not json", '{"partySize": 100, "time": "19:30"}']:
            await assert_mcp_error(
                client.session.call_tool(
                    "book_table",
                    {"restaurant": "Chez Nous"},
                    input_responses={"confirmation": accept({"confirm": True})},
                    request_state=request_state,
                    allow_input_required=True,
                ),
                types.INVALID_PARAMS,
                "Invalid requestState",
            )


# ---------------------------
# Resources
# ---------------------------


@test
async def test_list_resources() -> None:
    async with connect() as client:
        resources = {resource.uri: resource for resource in (await client.list_resources()).resources}
        assert_equal(sorted(resources), ["demo://readme", "demo://time"])
        assert_equal(resources["demo://readme"].mime_type, "text/markdown")


@test
async def test_read_readme_own_cache_hints() -> None:
    async with connect() as client:
        result = await client.read_resource("demo://readme")
        assert_equal(len(result.contents), 1)
        contents = result.contents[0]
        assert isinstance(contents, types.TextResourceContents)
        assert_equal(contents.mime_type, "text/markdown")
        assert contents.text.startswith("# mcp-demo-server"), contents.text
        assert_equal(result.ttl_ms, 86_400_000)


@test
async def test_read_time_default_cache_hints() -> None:
    async with connect() as client:
        result = await client.read_resource("demo://time")
        contents = result.contents[0]
        assert isinstance(contents, types.TextResourceContents)
        assert_equal(contents.mime_type, "text/plain")
        assert contents.text.endswith("Z"), f"not an ISO-8601 instant: {contents.text}"
        assert_equal(result.ttl_ms, 60_000)
        assert_equal(result.cache_scope, "public")


@test
async def test_unknown_resource() -> None:
    async with connect() as client:
        error = await assert_mcp_error(client.read_resource("demo://nope"), types.INVALID_PARAMS)
        assert_equal(error.error.data, {"uri": "demo://nope"})


@test
async def test_list_resource_templates() -> None:
    async with connect() as client:
        templates = (await client.list_resource_templates()).resource_templates
        assert_equal([template.uri_template for template in templates], ["demo://weather/{city}"])


@test
async def test_read_resource_template() -> None:
    async with connect() as client:
        result = await client.read_resource("demo://weather/Lisbon")
        contents = result.contents[0]
        assert isinstance(contents, types.TextResourceContents)
        assert_equal(contents.uri, "demo://weather/Lisbon")
        assert contents.text.startswith("Sunny in Lisbon, "), contents.text
        assert_equal(result.ttl_ms, 600_000)
        assert_equal(result.cache_scope, "public")


# ---------------------------
# Prompts
# ---------------------------


@test
async def test_list_prompts() -> None:
    async with connect() as client:
        prompts = (await client.list_prompts()).prompts
        assert_equal([prompt.name for prompt in prompts], ["code_review"])
        arguments = prompts[0].arguments or []
        assert_equal([(argument.name, argument.required) for argument in arguments], [("language", True), ("code", True)])


@test
async def test_get_prompt() -> None:
    async with connect() as client:
        result = await client.get_prompt("code_review", {"language": "java", "code": "int x = 1;"})
        assert_equal(result.description, "Review of java code")
        assert_equal(len(result.messages), 1)
        message = result.messages[0]
        assert_equal(message.role, "user")
        assert isinstance(message.content, types.TextContent)
        assert_equal(message.content.text, "Please review this java code:\n\nint x = 1;")


@test
async def test_unknown_prompt() -> None:
    async with connect() as client:
        await assert_mcp_error(client.get_prompt("nope", {}), types.INVALID_PARAMS, "Unknown prompt: nope")


# ---------------------------
# Completions
# ---------------------------


@test
async def test_complete_prompt_argument() -> None:
    async with connect() as client:
        result = await client.complete(
            types.PromptReference(type="ref/prompt", name="code_review"), {"name": "language", "value": "ja"}
        )
        assert_equal(result.completion.values, ["java", "javascript"])
        assert_equal(result.completion.total, 2)
        assert_equal(result.completion.has_more, False)


@test
async def test_complete_resource_template_argument() -> None:
    async with connect() as client:
        result = await client.complete(
            types.ResourceTemplateReference(type="ref/resource", uri="demo://weather/{city}"),
            {"name": "city", "value": "p"},
        )
        assert_equal(result.completion.values, ["Paris", "Porto"])


@test
async def test_complete_unknown_argument() -> None:
    async with connect() as client:
        result = await client.complete(
            types.PromptReference(type="ref/prompt", name="code_review"), {"name": "code", "value": "x"}
        )
        assert_equal(result.completion.values, [])


# ---------------------------
# Runner
# ---------------------------


def jar_is_stale() -> bool:
    if not JAR.exists():
        return True
    built = JAR.stat().st_mtime
    for source in JAR_SOURCES:
        files = [source] if source.is_file() else (path for path in source.rglob("*") if path.is_file())
        if any(path.stat().st_mtime > built for path in files):
            return True
    return False


def build_jar() -> None:
    print("Building mcp-demo-server...", flush=True)
    command = [str(REPO_ROOT / "mvnw"), "-q", "-pl", "mcp-demo-server", "-am", "package", "-DskipTests"]
    completed = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
    if completed.returncode != 0:
        print(completed.stdout, completed.stderr, sep="\n", file=sys.stderr)
        sys.exit(f"Build failed: {' '.join(command)}")


def use_color() -> bool:
    return sys.stdout.isatty()


def colored(text: str, code: str) -> str:
    return f"\033[{code}m{text}\033[0m" if use_color() else text


async def run_test(t: Test) -> tuple[bool, str | None]:
    try:
        with anyio.fail_after(TEST_TIMEOUT_SECONDS):
            await t.fn()
        return True, None
    except TimeoutError:
        return False, f"timed out after {TEST_TIMEOUT_SECONDS}s"
    except Exception as error:  # noqa: BLE001 - any failure is reported, and the next test runs
        if isinstance(error, AssertionError) and str(error):
            return False, f"AssertionError: {error}"
        return False, "".join(traceback.format_exception(error)).rstrip()


async def run(tests: list[Test]) -> int:
    width = max(len(t.name) for t in tests)
    passed = 0
    for t in tests:
        print(f"  {t.name:<{width}}  ", end="", flush=True)
        start = time.monotonic()
        success, failure = await run_test(t)
        elapsed = f"({time.monotonic() - start:.1f}s)"
        if success:
            passed += 1
            print(f"{colored('PASS', '32')} {elapsed}")
        else:
            print(f"{colored('FAIL', '31')} {elapsed}")
            for line in (failure or "").splitlines():
                print(f"      {line}")
    summary = f"{passed}/{len(tests)} succeeded"
    print()
    print(colored(summary, "32" if passed == len(tests) else "31"))
    if passed != len(tests):
        print(f"Server logs: {SERVER_LOG}")
    return 0 if passed == len(tests) else 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-k", dest="filter", help="only run the tests whose name contains this string")
    build = parser.add_mutually_exclusive_group()
    build.add_argument("--build", action="store_true", help="always rebuild the server jar")
    build.add_argument("--no-build", action="store_true", help="never build the server jar")
    parser.add_argument("--list", action="store_true", help="list the tests and exit")
    args = parser.parse_args()

    tests = [t for t in TESTS if args.filter is None or args.filter in t.name]
    if args.list:
        print("\n".join(t.name for t in tests))
        return
    if not tests:
        sys.exit(f"No test matches {args.filter!r}")

    if args.build or (not args.no_build and jar_is_stale()):
        build_jar()
    if not JAR.exists():
        sys.exit(f"Server jar not found: {JAR}")

    global _server_log
    SERVER_LOG.parent.mkdir(parents=True, exist_ok=True)
    with SERVER_LOG.open("w") as _server_log:
        print(f"Running {len(tests)} tests against {JAR.relative_to(REPO_ROOT)}\n")
        sys.exit(anyio.run(run, tests))


if __name__ == "__main__":
    main()

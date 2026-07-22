import asyncio

from asterism_worker.providers.session_store import JsonlSessionStore


def test_jsonl_session_store_persists_main_and_subagent_transcripts(tmp_path):
    store = JsonlSessionStore(tmp_path)
    main = {"project_key": "project", "session_id": "session"}
    child = {**main, "subpath": "subagents/agent-1"}

    async def exercise():
        await store.append(main, [{"type": "user", "uuid": "u1"}])
        await store.append(main, [{"type": "user", "uuid": "u1"}, {"type": "result", "uuid": "u2"}])
        await store.append(child, [{"type": "assistant", "uuid": "c1"}])
        moved = {"project_key": "moved-project", "session_id": "session"}
        return (
            await store.load(main),
            await store.load(child),
            await store.list_subkeys(main),
            await store.load(moved),
            await store.list_subkeys(moved),
        )

    main_entries, child_entries, subkeys, moved_entries, moved_subkeys = asyncio.run(exercise())

    assert [entry["uuid"] for entry in main_entries] == ["u1", "u2"]
    assert child_entries == [{"type": "assistant", "uuid": "c1"}]
    assert subkeys == ["subagents/agent-1"]
    assert moved_entries == main_entries
    assert moved_subkeys == subkeys
    assert store.contains("session") is True
    assert store.contains("missing") is False

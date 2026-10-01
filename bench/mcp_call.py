import json, sys, urllib.request, tiktoken
enc = tiktoken.get_encoding("o200k_base")
URL = "http://127.0.0.1:8080/mcp"
_id = [0]
def rpc(method, params=None):
    _id[0] += 1
    body = {"jsonrpc":"2.0","id":_id[0],"method":method}
    if params is not None: body["params"] = params
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={"Content-Type":"application/json","Accept":"application/json, text/event-stream"})
    return json.loads(urllib.request.urlopen(req, timeout=60).read())
def call(name, args=None):
    r = rpc("tools/call", {"name":name,"arguments":args or {}})
    if "result" not in r: return json.dumps(r), True
    return r["result"]["content"][0]["text"], r["result"].get("isError", False)
def tok(s): return len(enc.encode(s))
if __name__ == "__main__":
    rpc("initialize", {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"probe","version":"0"}})
    name = sys.argv[1]; args = json.loads(sys.argv[2]) if len(sys.argv)>2 else {}
    text, err = call(name, args)
    print(f"## {name} err={err} chars={len(text)} tokens~={tok(text)}")
    print(text)

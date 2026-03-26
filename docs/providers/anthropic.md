# Anthropic Provider

`d2t` supports the native Anthropic Messages API.

## Config

```toml
[ai]
enabled = true
provider = "anthropic"
protocol = "anthropic-messages"
api_key_env = "ANTHROPIC_API_KEY"
model = "claude-sonnet-4-5"
base_url = "https://api.anthropic.com/v1"
connect_timeout_seconds = 30
request_timeout_seconds = 300
```

## Environment

```bash
export ANTHROPIC_API_KEY="..."
```

## Verify

```bash
d2t doctor
d2t auto --ai
```

## Notes

- `d2t` calls `POST /v1/messages`
- authentication is sent with `x-api-key`
- `anthropic-version: 2023-06-01` is always attached
- use `--strict-ai` if you want the command to fail instead of falling back when AI generation is rejected

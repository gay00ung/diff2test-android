# Gemini Provider

`d2t` supports the native Gemini GenerateContent API.

## Config

```toml
[ai]
enabled = true
provider = "gemini"
protocol = "gemini-generate-content"
api_key_env = "GEMINI_API_KEY"
model = "gemini-2.5-pro"
base_url = "https://generativelanguage.googleapis.com/v1beta"
connect_timeout_seconds = 30
request_timeout_seconds = 300
```

## Environment

```bash
export GEMINI_API_KEY="..."
```

## Verify

```bash
d2t doctor
d2t auto --ai
```

## Notes

- `d2t` calls `POST /v1beta/models/{model}:generateContent`
- authentication is sent with `x-goog-api-key`
- the request asks Gemini for JSON output with the generated test schema
- use `--strict-ai` if you want the command to fail instead of falling back when AI generation is rejected

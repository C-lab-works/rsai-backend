<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# mapping

## Purpose
HTTP メソッドに対応したルートマッピングアノテーション群。`AnnotationScanner` がこれらを読み取ってルートを `Router` に登録する。

## Key Files

| File | Description |
|------|-------------|
| `GetMapping.java` | `@GetMapping("/path")` — GET ルート |
| `PostMapping.java` | `@PostMapping("/path")` — POST ルート |
| `PutMapping.java` | `@PutMapping("/path")` — PUT ルート |
| `DeleteMapping.java` | `@DeleteMapping("/path")` — DELETE ルート |
| `PatchMapping.java` | `@PatchMapping("/path")` — PATCH ルート |
| `WsMapping.java` | `@WsMapping("/path")` — WebSocket ルート |

## For AI Agents

### Working In This Directory
- アノテーションの `value()` はパスパターン（`:id` スタイルのパスパラメータ対応）
- 全アノテーションは `@Retention(RetentionPolicy.RUNTIME)` と `@Target(ElementType.METHOD)` を持つこと

<!-- MANUAL: -->

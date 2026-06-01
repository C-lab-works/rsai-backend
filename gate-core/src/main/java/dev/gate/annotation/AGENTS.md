<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-06-01 | Updated: 2026-06-01 -->

# annotation

## Purpose
Gate フレームワークのコントローラー検出・ルート登録に使用するカスタムアノテーション定義。`AnnotationScanner` がコントローラークラスをスキャンしてルートを自動登録する。

## Key Files

| File | Description |
|------|-------------|
| `GateController.java` | クラスレベルアノテーション。これが付いたクラスをコントローラーとして認識 |
| `AnnotationScanner.java` | `@GateController` クラスを受け取り、`@GetMapping` 等のメソッドアノテーションを走査して `Router` に登録 |

## For AI Agents

### Working In This Directory
- 新しい HTTP メソッドアノテーションを追加する場合は `mapping/` ディレクトリに追加し、`AnnotationScanner` も更新すること
- GraalVM native image ではリフレクションが制限されるため、新規アノテーションを追加する場合は `reflect-config.json` への登録が必要な場合がある

<!-- MANUAL: -->

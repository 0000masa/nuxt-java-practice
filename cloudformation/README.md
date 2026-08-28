# CloudFormation テンプレート

```
cloudformation/
├── app.yml            共通。全リソースの定義(このファイルは環境ごとに分けない)
├── params/
│   ├── stg.json       stg の環境差分(Terraform の tfvars 相当)
│   └── prod.json      prod のひな形。建てる予定は無いが、差分の置き場を示すために置いてある
└── README.md
```

- 設計と決定の理由 → [docs/superpowers/specs/2026-08-19-phase13-cloudformation-design.md](../docs/superpowers/specs/2026-08-19-phase13-cloudformation-design.md)
- 手動セットアップと構築・撤収の手順 → [docs/infrastructure/cloudformation-operations.md](../docs/infrastructure/cloudformation-operations.md)
- 全体方針 → [docs/infrastructure/README.md](../docs/infrastructure/README.md)

## このディレクトリを触るときに知っておくこと

**環境差分は `params/` にしか書かない。** `app.yml` に環境名ごとの値(`Mappings` の stg / prod など)を持ち込まないこと。prod を追加するときに共通部分を編集しないで済む状態を保つのが、この分け方の目的。

**`params/` に秘密を置かない。** デプロイのたびに変わる値と秘密は、ワークフローが `--parameter-overrides` で渡す。

| パラメータ | 渡し元 |
|---|---|
| `ImageTag` | ワークフローの input(`ecr-push.yml` のサマリに出る短縮 SHA)。`cfn-deploy.yml` は必須、`cfn-apply.yml` は任意で空なら現行維持 |
| `WebDesiredCount` | `cfn-deploy.yml`(1 段目は 0、4 段目で `params` の値)。`cfn-apply.yml` は `params` の値をそのまま渡す |
| `BasicAuthCredential` | GitHub の Environment secret |
| `HostedZoneId` | `params`。秘密ではないがアカウント固有なので手で埋める |
| `SlackWorkspaceId` / `SlackChannelIdEcs` / `SlackChannelIdRds` | `params`。同上。**Slack の ID は秘密ではない**(認可済みの AWS アカウントからしか使えないため)→ [docs/slack/README.md](../docs/slack/README.md) |

**`app.yml` は 51,200 バイトを超えている。** CloudFormation が**リクエストに直接受け取れるテンプレートの上限**で、`CreateStack` / `UpdateStack` / **`ValidateTemplate`** に等しく掛かる。そのため:

- ワークフローはテンプレートを S3 に置いてから渡す(`deploy --s3-bucket` / `create-change-set --template-url`)。バケットは手動管理の常駐リソース → [手順書 §3](../docs/infrastructure/cloudformation-operations.md)
- **`aws cloudformation validate-template --template-body file://...` は使えない。** ローカルで構文を見るなら `cfn-lint`(API を呼ばないので資格情報も不要)

```bash
# 手元での構文チェック(pipx install cfn-lint / pip install cfn-lint)
cfn-lint cloudformation/app.yml

# 差分だけ見る(スタックには何も起きない)
# 構築前  … Actions → 「CloudFormation スタックを作成/更新」を dry_run=true
# 構築済み … Actions → 「CloudFormation スタックを反映(更新のみ)」を dry_run=true
```

日本語コメントは 1 文字 3 バイトなので、**行数よりバイト数が先に上限へ当たる。** 大きく書き足すときは `wc -c cloudformation/app.yml` を見る癖をつける(S3 経由になった今の上限は 1 MB)。

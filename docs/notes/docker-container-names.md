# コンテナ名とサービス名 — container_name を付けない理由

「docker-compose.yml に `container_name` がないから、`docker exec` のたびに `docker ps` で名前を確認しないといけないのでは?」という疑問から始まった話。結論: **確認は不要で、`container_name` も付けない**。

## サービス名とコンテナ名は別物

| | サービス名 | コンテナ名 |
|---|---|---|
| 誰が決めるか | docker-compose.yml の `services:` のキー(自分で書く) | Docker が自動生成(または `container_name:` で固定) |
| このリポジトリでの例 | `backend` | `nuxt-java-practice-backend-1` |
| 使うコマンド | `docker compose exec` / `logs` / `restart` など | 素の `docker exec` / `docker logs` など |

自動生成される名前は `<プロジェクト名>-<サービス名>-<連番>` という**規則的な形で毎回同じ**。プロジェクト名はデフォルトで docker-compose.yml のあるディレクトリ名(このリポジトリなら `nuxt-java-practice`)になる。「起動のたびにランダムな名前が付く」わけではない。

## docker exec と docker compose exec の違い

- `docker exec` は Docker デーモンに直接話しかけるコマンドで、compose.yml のことを知らない。だから実体の**コンテナ名**が必要
- `docker compose exec` は、まず compose.yml とプロジェクト名から「そのサービスに対応するコンテナはどれか」を**自分で探してから** exec する。だから**サービス名**でよい

```bash
# 同じことをする 2 通り
docker exec -it nuxt-java-practice-backend-1 bash   # コンテナ名が必要
docker compose exec backend bash                     # サービス名でよい(リポジトリ直下で実行)
```

つまり `docker ps` で名前を確認する必要はそもそもなく、リポジトリ直下で `docker compose exec <サービス名> <コマンド>` と打てばよい。`exec` に限らず `docker compose logs backend` / `docker compose restart backend` も同様。唯一の条件は「docker-compose.yml があるディレクトリで実行する」ことだけ。

## container_name を付けなかった理由

`container_name: backend` と書けばコンテナ名を短く固定でき、素の `docker exec backend ...` が打てるようになる。しかし代償がある:

- **同じ名前のコンテナはマシン上に 1 つしか作れない。** リポジトリをコピーして 2 環境同時に起動すると名前が衝突して起動できない
- `docker compose up --scale backend=2` のような**スケールが不可**になる(名前が 1 つしかないため)
- そもそも `docker compose exec` を使えば得るものがほぼない

「サービス名で操作する」という compose の標準の使い方に乗るほうが、設定も増えず副作用もないという判断。

### 補足: 名前を短くしたいだけなら

コンテナ名の長さの原因はプロジェクト名の部分。`.env` に `COMPOSE_PROJECT_NAME=njp` と書けば自動生成名が `njp-backend-1` になる。名前の一意性やスケール可能性は保ったまま短縮できる折衷案(今回は採用していない)。

## 用語集

- **サービス名** — compose.yml の `services:` のキー。compose コマンドとコンテナ間通信(サービス名 DNS)の両方でこの名前を使う
- **コンテナ名** — Docker デーモンが管理する実体の名前。`<プロジェクト名>-<サービス名>-<連番>` で自動生成される
- **プロジェクト名** — compose がリソース(コンテナ・ネットワーク・ボリューム)の名前の接頭辞に使う名前。デフォルトはディレクトリ名、`COMPOSE_PROJECT_NAME` で変更可
- **`container_name:`** — コンテナ名を固定する compose の設定。一意制約(同名は 1 つだけ)とスケール不可の副作用がある

## 関連

- サービス名がコンテナ間通信の宛先にもなる話(サービス名 DNS) → [docker-dev-containers.md](./docker-dev-containers.md)

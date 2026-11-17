# -*- coding: utf-8 -*-
"""把 GitHub 上发好的版本同步一份到 Gitee（国内下载快，不用科学上网）。

为什么单独一个脚本：
- CI 里跑一次，负责「建发行版 + 传 APK」；
- 本机也能手动补发某个旧版本（改 TAG 环境变量即可）。
- 只用 Python 标准库，跑在哪都不用装东西。

需要 3 个环境变量（CI 里来自仓库 Secrets）：
    GITEE_TOKEN   私人令牌（Gitee 设置 → 私人令牌，勾 projects）
    GITEE_OWNER   Gitee 用户名
    GITEE_REPO    Gitee 仓库名（和 GitHub 那个同名最简单）
另有（CI 自动给）：
    TAG           版本 tag，如 v3.21.4
    APK_PATH      要传的 apk 路径
    BODY_PATH     发行版说明文件（可选）

安全：令牌只放在环境变量里，不打印、不写进日志。
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

API = "https://gitee.com/api/v5"
MAX_ASSET_BYTES = 100 * 1024 * 1024  # Gitee 免费账号单附件硬上限 100MB


def env(name, default=None, required=False):
    v = os.environ.get(name, default)
    if required and not v:
        sys.exit("缺少环境变量 %s" % name)
    return v


def call(method, path, token, params=None, data=None, headers=None, raw=False):
    url = API + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    body = None
    hdrs = {"User-Agent": "bellnote-release-bot"}
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        hdrs["Content-Type"] = "application/json;charset=UTF-8"
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = r.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        raise RuntimeError("%s %s -> HTTP %s\n%s" % (method, path, e.code, detail))
    if raw:
        return payload
    return json.loads(payload.decode("utf-8")) if payload else {}


def find_release(token, owner, repo, tag):
    try:
        return call("GET", "/repos/%s/%s/releases/tags/%s" % (owner, repo, tag),
                    token, params={"access_token": token})
    except RuntimeError as e:
        if "HTTP 404" in str(e):
            return None
        raise


def ensure_release(token, owner, repo, tag, version, body, target):
    rel = find_release(token, owner, repo, tag)
    if rel:
        print("Gitee 上已有 %s 的发行版（id=%s）" % (tag, rel.get("id")))
        return rel
    print("Gitee 上还没有 %s 的发行版，创建中…" % tag)
    rel = call("POST", "/repos/%s/%s/releases" % (owner, repo), token, data={
        "access_token": token,
        "tag_name": tag,
        "name": "Bellnote %s" % version,
        "body": body,
        "target_commitish": target,
        "prerelease": False,
    })
    print("已创建发行版 id=%s" % rel.get("id"))
    return rel


def existing_assets(token, owner, repo, release_id):
    data = call("GET", "/repos/%s/%s/releases/%s/attach_files" % (owner, repo, release_id),
                token, params={"access_token": token, "per_page": 100})
    return [a.get("name") or a.get("title") for a in (data or [])]


def upload(token, owner, repo, release_id, apk_path):
    name = os.path.basename(apk_path)
    size = os.path.getsize(apk_path)
    if size > MAX_ASSET_BYTES:
        sys.exit("APK %d 字节，超过 Gitee 单附件 100MB 上限" % size)
    boundary = "----bellnote" + uuid.uuid4().hex
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    with open(apk_path, "rb") as f:
        blob = f.read()
    parts = []
    parts.append(("--%s\r\n" % boundary).encode())
    parts.append(('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode())
    parts.append(("Content-Type: %s\r\n\r\n" % ctype).encode())
    parts.append(blob)
    parts.append(("\r\n--%s--\r\n" % boundary).encode())
    body = b"".join(parts)
    url = "%s/repos/%s/%s/releases/%s/attach_files?access_token=%s" % (
        API, owner, repo, release_id, urllib.parse.quote(token))
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "multipart/form-data; boundary=%s" % boundary,
        "Content-Length": str(len(body)),
        "User-Agent": "bellnote-release-bot",
    })
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError("上传 %s 失败 HTTP %s: %s" % (
            name, e.code, e.read().decode("utf-8", "replace")[:600]))


def main():
    token = env("GITEE_TOKEN", required=True)
    owner = env("GITEE_OWNER", required=True)
    repo = env("GITEE_REPO", required=True)
    tag = env("TAG", required=True)
    version = tag.lstrip("v")
    apk = env("APK_PATH", required=True)
    target = env("GITEE_BRANCH", "master")
    if not os.path.exists(apk):
        sys.exit("找不到 APK：%s" % apk)

    body_path = env("BODY_PATH")
    if body_path and os.path.exists(body_path):
        body = open(body_path, encoding="utf-8").read()
    else:
        body = "Bellnote %s 正式版。包里版本号与 tag 一致，签名与 GitHub 发布版相同。" % version

    rel = ensure_release(token, owner, repo, tag, version, body, target)
    name = os.path.basename(apk)
    if name in existing_assets(token, owner, repo, rel["id"]):
        print("附件 %s 已经在，跳过上传（幂等）" % name)
    else:
        upload(token, owner, repo, rel["id"], apk)
        print("已上传 %s" % name)

    url = "https://gitee.com/%s/%s/releases/download/%s/%s" % (owner, repo, tag, name)
    print("GITEE_DOWNLOAD_URL=%s" % url)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write("### 🇨🇳 Gitee 国内镜像\n\n- 下载：%s\n" % url)


if __name__ == "__main__":
    main()

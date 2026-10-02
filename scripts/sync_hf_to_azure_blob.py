#!/usr/bin/env python3
"""
Download parquet files from a Hugging Face repo and upload them to Azure Blob Storage.

Examples:
  python3 scripts/sync_hf_to_azure_blob.py \
    --repo-id user/sec-10k-parquet \
    --repo-type dataset

  python3 scripts/sync_hf_to_azure_blob.py \
    --repo-id user/sec-10k-parquet \
    --repo-type dataset \
    --path filings/ \
    --revision main
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


HF_API_BASE = "https://huggingface.co/api"
HF_BASE = "https://huggingface.co"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sync parquet files from Hugging Face to Azure Blob Storage."
    )
    parser.add_argument("--repo-id", required=True, help="Hugging Face repo id, for example user/my-dataset")
    parser.add_argument(
        "--repo-type",
        choices=("dataset", "model"),
        default="dataset",
        help="Repo type on Hugging Face. Defaults to dataset.",
    )
    parser.add_argument("--revision", default="main", help="Repo revision, tag, or branch. Defaults to main.")
    parser.add_argument(
        "--path",
        default="",
        help="Optional subdirectory within the repo to scan for parquet files.",
    )
    parser.add_argument(
        "--container",
        default=os.environ.get("AZURE_BLOB_CONTAINER_NAME", "10kfilestorage"),
        help="Azure Blob container name. Defaults to AZURE_BLOB_CONTAINER_NAME or 10kfilestorage.",
    )
    parser.add_argument(
        "--connection-string",
        default=os.environ.get("AZURE_BLOB_CONNECTION_STRING", ""),
        help="Azure Blob connection string. Defaults to AZURE_BLOB_CONNECTION_STRING.",
    )
    parser.add_argument(
        "--hf-token",
        default=os.environ.get("HF_TOKEN", ""),
        help="Optional Hugging Face token for private or gated repos.",
    )
    parser.add_argument(
        "--prefix",
        default="",
        help="Optional blob key prefix to prepend when uploading to Azure.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List matching parquet files without downloading or uploading them.",
    )
    return parser.parse_args()


def build_headers(hf_token: str) -> dict[str, str]:
    headers = {"User-Agent": "FilingLens/1.0"}
    if hf_token:
        headers["Authorization"] = f"Bearer {hf_token}"
    return headers


def list_repo_files(repo_id: str, repo_type: str, revision: str, path: str, headers: dict[str, str]) -> list[str]:
    encoded_repo_id = urllib.parse.quote(repo_id, safe="")
    encoded_revision = urllib.parse.quote(revision, safe="")
    normalized_path = path.strip("/")
    api_path = (
        f"{HF_API_BASE}/{repo_type}s/{encoded_repo_id}/tree/{encoded_revision}/{normalized_path}"
        if normalized_path
        else f"{HF_API_BASE}/{repo_type}s/{encoded_repo_id}/tree/{encoded_revision}"
    )
    query = urllib.parse.urlencode({"recursive": "1", "expand": "0"})
    request = urllib.request.Request(f"{api_path}?{query}", headers=headers)

    try:
        with urllib.request.urlopen(request) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to list Hugging Face files: HTTP {exc.code}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Failed to reach Hugging Face: {exc.reason}") from exc

    files: list[str] = []
    for item in payload:
        item_type = item.get("type")
        item_path = item.get("path")
        if item_type == "file" and isinstance(item_path, str):
            files.append(item_path)
    return sorted(file for file in files if file.endswith(".parquet"))


def download_file(repo_id: str, repo_type: str, revision: str, file_path: str, destination: Path, headers: dict[str, str]) -> None:
    encoded_repo_id = urllib.parse.quote(repo_id, safe="/")
    encoded_revision = urllib.parse.quote(revision, safe="")
    encoded_file_path = "/".join(urllib.parse.quote(part, safe="") for part in file_path.split("/"))
    repo_segment = "datasets" if repo_type == "dataset" else repo_type
    url = f"{HF_BASE}/{repo_segment}/{encoded_repo_id}/resolve/{encoded_revision}/{encoded_file_path}?download=true"
    request = urllib.request.Request(url, headers=headers)

    try:
        with urllib.request.urlopen(request) as response, destination.open("wb") as output_file:
            shutil.copyfileobj(response, output_file)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Failed to download {file_path}: HTTP {exc.code}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Failed to download {file_path}: {exc.reason}") from exc


def upload_to_azure(local_path: Path, blob_name: str, container: str, connection_string: str) -> None:
    command = [
        "az",
        "storage",
        "blob",
        "upload",
        "--only-show-errors",
        "--overwrite",
        "true",
        "--connection-string",
        connection_string,
        "--container-name",
        container,
        "--name",
        blob_name,
        "--file",
        str(local_path),
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        message = result.stderr.strip() or result.stdout.strip() or "unknown Azure CLI error"
        raise RuntimeError(f"Failed to upload {blob_name}: {message}")


def ensure_azure_cli() -> None:
    if shutil.which("az") is None:
        raise RuntimeError("Azure CLI is required. Install it before running this script.")


def blob_name_for(prefix: str, file_path: str) -> str:
    normalized_prefix = prefix.strip("/")
    return f"{normalized_prefix}/{file_path}" if normalized_prefix else file_path


def main() -> int:
    args = parse_args()

    if not args.connection_string and not args.dry_run:
        print("AZURE_BLOB_CONNECTION_STRING is required unless --dry-run is used.", file=sys.stderr)
        return 1

    headers = build_headers(args.hf_token)
    parquet_files = list_repo_files(args.repo_id, args.repo_type, args.revision, args.path, headers)

    if not parquet_files:
        print("No parquet files found.")
        return 0

    print(f"Found {len(parquet_files)} parquet file(s):")
    for file_path in parquet_files:
        print(f" - {file_path}")

    if args.dry_run:
        return 0

    ensure_azure_cli()

    with tempfile.TemporaryDirectory(prefix="filinglens-hf-sync-") as temp_dir:
        temp_root = Path(temp_dir)
        for file_path in parquet_files:
            local_name = file_path.replace("/", "__")
            local_path = temp_root / local_name
            blob_name = blob_name_for(args.prefix, file_path)
            print(f"Downloading {file_path}...")
            download_file(args.repo_id, args.repo_type, args.revision, file_path, local_path, headers)
            print(f"Uploading {blob_name} to container {args.container}...")
            upload_to_azure(local_path, blob_name, args.container, args.connection_string)

    print(f"Uploaded {len(parquet_files)} parquet file(s) to Azure Blob container {args.container}.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)

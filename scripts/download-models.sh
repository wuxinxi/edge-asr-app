#!/usr/bin/env bash
# ==============================================================================
# 端侧流式 ASR Demo 依赖与模型一键下载脚本（< 228MB SenseVoice 探底与主力梯队）
#
# 功能说明：
#   1. 下载 sherpa-onnx 官方预编译 Android AAR 至 app/libs/sherpa-onnx.aar
#   2. 下载端侧原生增强三件套权重（总计 0.73MB）：
#      - gtcrn/gtcrn_simple.onnx    : 0.51MB 实时神经网络语音降噪
#      - vad/silero_vad.int8.onnx   : 0.20MB 神经网络人声活动检测门控
#      - itn/itn_zh_number.fst      : 0.02MB WFST 中文口语数字逆文本规整
#   3. 按需下载 4 款官方纯流式 INT8 模型的推理必需文件至 app/src/main/assets/models/<模型ID>/：
#      - zipformer         : 24.2MB 极限探底参照（Zipformer 14M）
#      - multi_zh          : 72.8MB 纯中文起步线（Zipformer Multi-zh）
#      - zipformer_zh_2025 : 159.6MB 2025 纯中文高准主力（Zipformer-zh 2025）
#      - x_asr_zh_en       : 161.4MB 2026 中英双语 + 原生流式自带标点主力（X-ASR 480ms）
#
# 用法示例：
#   bash scripts/download-models.sh                           # 下载 AAR + 全部 4 款流式模型
#   bash scripts/download-models.sh x_asr_zh_en               # 下载 AAR + 指定主力模型
#   bash scripts/download-models.sh multi_zh zipformer_zh_2025
#   bash scripts/download-models.sh --aar-only                # 仅下载 Android AAR 依赖包
#
# 可选环境变量：
#   HF_ENDPOINT     Hugging Face 镜像地址，默认 https://hf-mirror.com
#   GITHUB_PROXY    GitHub Release 下载加速前缀，默认 https://gh-proxy.com/（失败自动回退直连）
#   SHERPA_VERSION  sherpa-onnx 版本号，默认 2.0.1
# ==============================================================================
set -euo pipefail

# ---------- 目录与全局配置 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIBS_DIR="${PROJECT_DIR}/app/libs"
ASSETS_MODELS_DIR="${PROJECT_DIR}/app/src/main/assets/models"
CACHE_DIR="${PROJECT_DIR}/.download-cache"

SHERPA_VERSION="${SHERPA_VERSION:-2.0.1}"
HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
HF_FALLBACK="https://huggingface.co"
GITHUB_PROXY="${GITHUB_PROXY:-https://gh-proxy.com/}"

# ---------- 4 款官方纯流式 INT8 模型清单（均严格小于 228MB SenseVoice） ----------
# 字段格式：模型ID|源类型(hf/gh_tar)|仓库或压缩包名|推理必需文件列表（空格分隔）
MODELS=(
  "zipformer|hf|csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23|encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt"
  "multi_zh|hf|csukuangfj/sherpa-onnx-streaming-zipformer-multi-zh-hans-int8-2023-12-13|encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx decoder-epoch-20-avg-1-chunk-16-left-128.onnx joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx tokens.txt"
  "zipformer_zh_2025|hf|csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30|encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt"
  "x_asr_zh_en|gh_tar|sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05|encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt"
)

# ---------- 日志与辅助函数 ----------
log_info()  { printf '\033[1;34m[INFO]\033[0m  %s\n' "$*"; }
log_ok()    { printf '\033[1;32m[OK]\033[0m    %s\n' "$*"; }
log_warn()  { printf '\033[1;33m[WARN]\033[0m  %s\n' "$*"; }
log_error() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    log_error "未找到必需命令: $1，请先安装后再运行脚本。"
    exit 1
  }
}

is_valid_zip() {
  local file="$1"
  [[ -s "${file}" ]] && unzip -tq "${file}" >/dev/null 2>&1
}

download_with_resume() {
  local url="$1"
  local out="$2"
  mkdir -p "$(dirname "${out}")"
  curl -fL --connect-timeout 15 --retry 5 --retry-delay 2 --retry-all-errors -C - -o "${out}" "${url}"
}

download_github_with_fallback() {
  local raw_url="$1"
  local out="$2"

  if [[ -n "${GITHUB_PROXY}" ]]; then
    local proxy_url="${GITHUB_PROXY%/}/${raw_url}"
    log_info "尝试加速镜像下载: ${proxy_url}"
    if download_with_resume "${proxy_url}" "${out}"; then
      return 0
    fi
    log_warn "加速镜像不可用，自动回退 GitHub 官方源直连..."
    rm -f "${out}"
  fi

  download_with_resume "${raw_url}" "${out}"
}

download_hf_file() {
  local repo="$1"
  local filename="$2"
  local out="$3"

  if [[ -s "${out}" ]]; then
    log_ok "已存在，跳过: $(basename "${out}")"
    return 0
  fi

  local tmp_out="${out}.part"
  local primary_url="${HF_ENDPOINT%/}/${repo}/resolve/main/${filename}"
  local fallback_url="${HF_FALLBACK}/${repo}/resolve/main/${filename}"

  log_info "下载模型文件: ${repo}/${filename}"
  if download_with_resume "${primary_url}" "${tmp_out}"; then
    mv "${tmp_out}" "${out}"
    return 0
  fi

  if [[ "${HF_ENDPOINT%/}" != "${HF_FALLBACK}" ]]; then
    log_warn "镜像源下载失败，自动切换 Hugging Face 官方源重试..."
    rm -f "${tmp_out}"
    if download_with_resume "${fallback_url}" "${tmp_out}"; then
      mv "${tmp_out}" "${out}"
      return 0
    fi
  fi

  log_error "文件下载失败: ${filename}"
  return 1
}

download_gh_tar_model() {
  local archive_stem="$1"
  local files="$2"
  local model_dir="$3"

  local all_ready=1 file
  for file in ${files}; do
    if [[ ! -s "${model_dir}/${file}" ]]; then
      all_ready=0
      break
    fi
  done
  if [[ ${all_ready} -eq 1 ]]; then
    log_ok "已存在，跳过解压: ${archive_stem}"
    return 0
  fi

  local tar_file="${CACHE_DIR}/${archive_stem}.tar.bz2"
  local raw_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${archive_stem}.tar.bz2"

  if [[ ! -s "${tar_file}" ]]; then
    log_info "下载模型归档包: ${archive_stem}.tar.bz2"
    download_github_with_fallback "${raw_url}" "${tar_file}.part"
    mv "${tar_file}.part" "${tar_file}"
  fi

  log_info "解压所需 INT8 权重与词表文件..."
  tar -xjf "${tar_file}" -C "${CACHE_DIR}"
  for file in ${files}; do
    cp -f "${CACHE_DIR}/${archive_stem}/${file}" "${model_dir}/${file}"
  done
  rm -rf "${CACHE_DIR:?}/${archive_stem}"
}

download_aar() {
  local aar_name="sherpa-onnx-${SHERPA_VERSION}.aar"
  local target_aar="${LIBS_DIR}/sherpa-onnx.aar"
  local cache_aar="${CACHE_DIR}/${aar_name}"

  mkdir -p "${LIBS_DIR}" "${CACHE_DIR}"

  if is_valid_zip "${target_aar}"; then
    log_ok "Android AAR 已就绪且校验通过: ${target_aar}"
    return 0
  fi

  local raw_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${aar_name}"
  log_info "正在下载 sherpa-onnx Android AAR (v${SHERPA_VERSION})..."
  download_github_with_fallback "${raw_url}" "${cache_aar}"

  if ! is_valid_zip "${cache_aar}"; then
    log_error "下载的 AAR 文件不完整或已损坏，请重新运行脚本继续下载: ${cache_aar}"
    exit 1
  fi

  cp "${cache_aar}" "${target_aar}"
  log_ok "AAR 部署完成: ${target_aar} ($(du -sh "${target_aar}" | cut -f1))"
}

download_gtcrn() {
  local gtcrn_dir="${ASSETS_MODELS_DIR}/gtcrn"
  local target_onnx="${gtcrn_dir}/gtcrn_simple.onnx"
  mkdir -p "${gtcrn_dir}"

  if [[ -s "${target_onnx}" ]]; then
    log_ok "GTCRN 神经网络降噪权重已就绪: ${target_onnx}"
    return 0
  fi

  local raw_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"
  log_info "正在下载 GTCRN 实时语音降噪模型 (0.51MB)..."
  download_github_with_fallback "${raw_url}" "${target_onnx}.part"
  mv "${target_onnx}.part" "${target_onnx}"
  log_ok "GTCRN 降噪模型部署完成: ${target_onnx} ($(du -sh "${target_onnx}" | cut -f1))"
}

download_vad_and_itn() {
  local vad_dir="${ASSETS_MODELS_DIR}/vad"
  local itn_dir="${ASSETS_MODELS_DIR}/itn"
  local target_vad="${vad_dir}/silero_vad.int8.onnx"
  local target_itn="${itn_dir}/itn_zh_number.fst"
  mkdir -p "${vad_dir}" "${itn_dir}"

  if [[ -s "${target_vad}" ]]; then
    log_ok "Silero VAD INT8 权重已就绪: ${target_vad}"
  else
    local vad_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx"
    log_info "正在下载 Silero VAD INT8 人声检测模型 (0.20MB)..."
    download_github_with_fallback "${vad_url}" "${target_vad}.part"
    mv "${target_vad}.part" "${target_vad}"
    log_ok "Silero VAD 部署完成: ${target_vad} ($(du -sh "${target_vad}" | cut -f1))"
  fi

  if [[ -s "${target_itn}" ]]; then
    log_ok "WFST 中文数字逆文本规整 FST 已就绪: ${target_itn}"
  else
    local itn_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/itn_zh_number.fst"
    log_info "正在下载 WFST 数字逆文本规整图 (0.02MB)..."
    download_github_with_fallback "${itn_url}" "${target_itn}.part"
    mv "${target_itn}.part" "${target_itn}"
    log_ok "WFST ITN 部署完成: ${target_itn} ($(du -sh "${target_itn}" | cut -f1))"
  fi
}

download_model_entry() {
  local id kind source files
  IFS='|' read -r id kind source files <<< "$1"

  local model_dir="${ASSETS_MODELS_DIR}/${id}"
  mkdir -p "${model_dir}"

  log_info "========== 开始准备流式模型 [${id}] =========="
  if [[ "${kind}" == "hf" ]]; then
    local file
    for file in ${files}; do
      download_hf_file "${source}" "${file}" "${model_dir}/${file}"
    done
  elif [[ "${kind}" == "gh_tar" ]]; then
    download_gh_tar_model "${source}" "${files}" "${model_dir}"
  else
    log_error "未知的模型下载源类型: ${kind}"
    exit 1
  fi

  log_ok "模型 [${id}] 准备完毕 ($(du -sh "${model_dir}" | cut -f1)) -> ${model_dir}"
}

# ---------- 主入口 ----------
main() {
  require_cmd curl
  require_cmd unzip
  require_cmd tar

  mkdir -p "${CACHE_DIR}" "${LIBS_DIR}" "${ASSETS_MODELS_DIR}"

  if [[ "${1:-}" == "--aar-only" ]]; then
    download_aar
    exit 0
  fi

  download_aar
  download_gtcrn
  download_vad_and_itn

  local requested=("$@")
  local entry id matched_any=0

  for entry in "${MODELS[@]}"; do
    id="${entry%%|*}"
    if [[ ${#requested[@]} -gt 0 ]]; then
      local keep_this=0 req
      for req in "${requested[@]}"; do
        if [[ "${req}" == "${id}" ]]; then
          keep_this=1
          break
        fi
      done
      [[ ${keep_this} -eq 1 ]] || continue
    fi
    matched_any=1
    download_model_entry "${entry}"
  done

  if [[ ${matched_any} -eq 0 ]]; then
    log_error "未匹配到指定模型 ID: ${requested[*]}（可选值: zipformer, multi_zh, zipformer_zh_2025, x_asr_zh_en）"
    exit 1
  fi

  echo ""
  log_ok "全部依赖与模型资源已就绪，现在可以在 Android Studio 中直接编译运行！"
}

main "$@"

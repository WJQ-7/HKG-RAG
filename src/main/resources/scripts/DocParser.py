import json
import os
import sys
import tempfile
from pathlib import Path
from markitdown import MarkItDown
import uuid
import io

# 修复 Windows 中文乱码
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

# 处理 .doc 老版文档
try:
    import textract
except ImportError:
    textract = None


def safe_print(msg):
    """
    解决 Windows 控制台编码问题，
    避免打印中文或特殊字符时程序崩溃
    """
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode("gbk", "ignore").decode("gbk"))


def load_documents_to_json(root_dir: str, output_json_path: str):
    result = []
    md = MarkItDown()

    # 支持的外部文件类型
    ALLOWED_EXT = {
        ".zip",
        ".rar",
        ".docx",
        ".doc",
        ".pdf",
        ".txt"
    }

    # 压缩包内部支持的文件类型
    ALLOWED_INNER_EXT = {
        ".docx",
        ".doc",
        ".pdf",
        ".txt"
    }

    # 递归遍历目录中的所有文件
    for path in Path(root_dir).rglob("*"):

        if not path.is_file():
            continue

        suffix = path.suffix.lower()

        # 跳过不支持的文件
        if suffix not in ALLOWED_EXT:
            safe_print(f"⏭️ 跳过不支持的文件: {path}")
            continue

        safe_print(f"处理: {path}")

        try:

            # ==================================================
            # 处理 ZIP / RAR 压缩包
            # ==================================================
            if suffix in [".zip", ".rar"]:

                with tempfile.TemporaryDirectory() as temp_dir:

                    # 解压压缩包
                    try:
                        md.convert(
                            str(path),
                            output_dir=temp_dir
                        )

                    except Exception as zip_err:

                        safe_print(
                            f"⚠️ 压缩包解析失败，已跳过: "
                            f"{path} | 错误: {str(zip_err)[:150]}"
                        )

                        continue

                    # 遍历压缩包内部文件
                    for inner_file in Path(temp_dir).rglob("*"):

                        if not inner_file.is_file():
                            continue

                        inner_suffix = inner_file.suffix.lower()

                        # 跳过压缩包内不支持的文件
                        if inner_suffix not in ALLOWED_INNER_EXT:

                            safe_print(
                                f"⏭️ 跳过压缩包内不支持的文件: "
                                f"{inner_file.name}"
                            )

                            continue

                        inner_content = ""

                        try:

                            # -----------------------------
                            # 处理老版 .doc
                            # -----------------------------
                            if inner_suffix == ".doc":

                                if textract is None:

                                    safe_print(
                                        f"⚠️ 未安装 textract，"
                                        f"无法处理 .doc: {inner_file.name}"
                                    )

                                    continue

                                text = textract.process(
                                    str(inner_file),
                                    encoding="utf-8"
                                )

                                inner_content = text.decode(
                                    "utf-8",
                                    errors="ignore"
                                ).strip()

                            # -----------------------------
                            # 处理 docx / pdf / txt
                            # -----------------------------
                            else:

                                inner_res = md.convert(
                                    str(inner_file)
                                )

                                inner_content = (
                                    inner_res
                                    .text_content
                                    .strip()
                                )

                        except Exception as ee:

                            safe_print(
                                f"⚠️ 文件内容提取失败: "
                                f"{inner_file.name} | "
                                f"错误: {str(ee)[:150]}"
                            )

                            continue

                        # -----------------------------
                        # 写入结果
                        # -----------------------------
                        if inner_content:

                            doc_id = str(uuid.uuid4())

                            result.append({
                                "fileId": doc_id,
                                "chunkId": "",
                                "fileName": inner_file.name,
                                "content": inner_content
                            })

            # ==================================================
            # 处理普通独立文件
            # ==================================================
            else:

                content = ""

                # -----------------------------
                # 处理老版 .doc
                # -----------------------------
                if suffix == ".doc":

                    if textract is None:

                        safe_print(
                            f"⚠️ 未安装 textract，"
                            f"无法处理 .doc: {path.name}"
                        )

                        continue

                    text = textract.process(
                        str(path),
                        encoding="utf-8"
                    )

                    content = text.decode(
                        "utf-8",
                        errors="ignore"
                    ).strip()

                # -----------------------------
                # 处理 docx / pdf / txt
                # -----------------------------
                else:

                    conv_result = md.convert(
                        str(path)
                    )

                    content = (
                        conv_result
                        .text_content
                        .strip()
                    )

                # -----------------------------
                # 写入结果
                # -----------------------------
                if content:

                    doc_id = str(uuid.uuid4())

                    result.append({
                        "fileId": doc_id,
                        "chunkId": "",
                        "fileName": path.name,
                        "content": content
                    })

        except Exception as e:

            safe_print(
                f"⚠️ 处理失败，跳过: "
                f"{path} | 错误: {str(e)[:150]}"
            )

    # ==================================================
    # 输出 JSON
    # ==================================================

    output_dir = os.path.dirname(output_json_path)

    if output_dir:
        os.makedirs(
            output_dir,
            exist_ok=True
        )

    with open(
        output_json_path,
        "w",
        encoding="utf-8"
    ) as f:

        json.dump(
            result,
            f,
            ensure_ascii=False,
            indent=2
        )

    # ==================================================
    # 输出统计信息
    # ==================================================

    safe_print(
        f"\n✅ 文档提取完成！"
        f"共提取：{len(result)} 篇有效文档"
    )

    safe_print(
        f"✅ JSON 已保存到："
        f"{output_json_path}"
    )

    return result


# ==================================================
# 程序入口
# ==================================================

if __name__ == "__main__":

    if len(sys.argv) != 3:

        print(
            "用法: "
            "python document_extract.py "
            "<输入文档目录> "
            "<输出json路径>"
        )

        sys.exit(1)

    INPUT_DIR = sys.argv[1]
    OUTPUT_JSON = sys.argv[2]

    load_documents_to_json(
        INPUT_DIR,
        OUTPUT_JSON
    )
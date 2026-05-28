package com.bistu.hkgrag.llm.model.agent;

import com.bistu.hkgrag.dao.milvus.model.DoFile;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 多智能体流程返回结果
 * @author wangjingq
 */
@Getter
@Setter
@NoArgsConstructor
public class RagResult {
    /**
     * 多智能体返回的最终答案
     */
    public String answer;
    /**
     * 得出最终答案所用到的文档
     */
    public List<DoFile> files;
}

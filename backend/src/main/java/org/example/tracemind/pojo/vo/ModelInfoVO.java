package org.example.tracemind.pojo.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 模型配置响应体，契约见说明书 3.3 节系统接口 **{@code GET /api/settings/model}**。
 *
 * <p>纯配置透传，读 application.yml 里的模型配置，不查库、不调 Python。</p>
 */
@Data
public class ModelInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 如 openai（走 OpenAI 兼容协议指向 Ollama） */
    private String provider;

    /** 模型名，如 qwen2.5:7b */
    private String model;

    /** 推理服务地址，如 http://localhost:11434/v1 */
    private String baseUrl;
}

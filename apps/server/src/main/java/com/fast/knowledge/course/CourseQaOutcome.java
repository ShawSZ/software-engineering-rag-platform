package com.fast.knowledge.course;

import java.util.List;

public record CourseQaOutcome(String status,String answer,String errorCode,String errorMessage,boolean retryable,List<String> citations) {
    public static CourseQaOutcome insufficient() {
        return new CourseQaOutcome("NO_EVIDENCE",null,"EVIDENCE_INSUFFICIENT","当前资料不足以回答，请换个问法或补充资料",false,List.of());
    }
    public static CourseQaOutcome failed(String code) {
        String message=switch(code) {
            case "MODEL_NOT_CONFIGURED"->"问答服务尚未配置，请联系管理员";
            case "MODEL_TIMEOUT"->"模型响应超时，请稍后重试";
            case "MODEL_SERVICE_ERROR"->"模型服务暂时不可用，请稍后重试";
            case "INVALID_CITATION"->"回答来源校验失败，请重试";
            case "INVALID_GENERATION"->"生成内容校验失败，请重试";
            case "EVIDENCE_CHANGED"->"资料已更新或下架，请重试";
            case "RETRIEVAL_ERROR"->"资料检索暂时不可用，请稍后重试";
            default->"问答服务异常，请稍后重试";
        };
        return new CourseQaOutcome("FAILED",null,code,message,!code.equals("MODEL_NOT_CONFIGURED"),List.of());
    }
}

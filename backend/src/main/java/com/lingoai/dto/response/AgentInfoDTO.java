package com.lingoai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 角色信息 DTO
 * 来源参考：everyone-can-use-english AGENT_FIXTURE_AVA / AGENT_FIXTURE_ANDREW
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInfoDTO {
    private String name;
    private String description;
    private String language;
    private String ttsVoice;
    private String ttsModel;
}

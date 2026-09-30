package com.ethlo.r7.status.dto;

import java.util.Map;

import com.ethlo.r7.journal.api.JournalLevel;

public record JournalDto(JournalLevel requestLevel, Map<String, JournalLevel> requestOverrides,
                         JournalLevel responseLevel, Map<String, JournalLevel> responseOverrides)
{
}

package com.mindtrace.agent;

import com.mindtrace.entity.CaseFile;
import com.mindtrace.entity.CaseTimeline;
import com.mindtrace.entity.ChatMessage;
import com.mindtrace.entity.Clue;
import com.mindtrace.entity.Npc;
import com.mindtrace.entity.NpcKnowledge;
import com.mindtrace.entity.Suspect;
import com.mindtrace.entity.User;

import java.util.List;

public record AgentContext(
        User user,
        CaseFile caseFile,
        Npc npc,
        String playerQuestion,
        List<CaseTimeline> timeline,
        List<Clue> discoveredClues,
        List<String> missingClueTopics,
        List<NpcKnowledge> npcKnowledge,
        List<Suspect> suspects,
        List<ChatMessage> recentMessages) {
}


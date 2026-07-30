package com.luiz.splitpix.group;

import com.luiz.splitpix.participant.Participant;
import java.util.List;

public record GroupView(Group group, List<Participant> participants) {
}

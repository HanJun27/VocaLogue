package com.lingoai.repository;

import com.lingoai.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息数据访问接口
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, String> {

    List<Message> findByConversationIdOrderByTimestampAsc(String conversationId);

    long countByConversationIdAndRole(String conversationId, String role);

    long countByConversationId(String conversationId);

}

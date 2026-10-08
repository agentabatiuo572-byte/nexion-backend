package ffdd.opsconsole.content.dto;

public record ConversationQueryRequest(
        String status,
        String type,
        String ownerAgentId,
        Long userId,
        String keyword,
        Boolean unreadOnly,
        Long pageNum,
        Long pageSize,
        Boolean archived,
        ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode readMode,
        Long groupId) {
    public ConversationQueryRequest(String status,String type,String ownerAgentId,Long userId,String keyword,Boolean unreadOnly,Long pageNum,Long pageSize,Boolean archived) {
        this(status,type,ownerAgentId,userId,keyword,unreadOnly,pageNum,pageSize,archived,null,null);
    }
    public ConversationQueryRequest(String status,String type,String ownerAgentId,Long userId,String keyword,Boolean unreadOnly,Long pageNum,Long pageSize) {
        this(status,type,ownerAgentId,userId,keyword,unreadOnly,pageNum,pageSize,null);
    }
}

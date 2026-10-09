package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;

/** Exercise real JSON tokens before a fractional or unsafe version can reach any writer. */
class SupportGroupVersionParsingTest {
    private final ObjectMapper json=new ObjectMapper();
    static Stream<Arguments> versionFields() {
        return Stream.of(
            Arguments.of(Rename.class,"expectedVersion",true),
            Arguments.of(Status.class,"expectedVersion",true),
            Arguments.of(Owner.class,"expectedVersion",true),
            Arguments.of(Move.class,"expectedMemberVersion",true),
            Arguments.of(Move.class,"sourceGroupVersion",false),
            Arguments.of(Move.class,"targetGroupVersion",false),
            Arguments.of(Qualification.class,"expectedQualificationVersion",true),
            Arguments.of(Qualification.class,"expectedAccountVersion",true),
            Arguments.of(Route.class,"expectedRouteVersion",true),
            Arguments.of(Route.class,"sourceGroupVersion",false),
            Arguments.of(Route.class,"targetGroupVersion",false));
    }
    static Stream<Arguments> invalidVersions() {
        return versionFields().flatMap(a->Stream.of("1.5","-1","9007199254740992","\"1\"","true","1e0")
            .map(value->Arguments.of(a.get()[0],a.get()[1],value)));
    }
    private ObjectNode request(Class<?> type) {
        Map<String,Object> values=new HashMap<>();
        values.put("name","group");values.put("supervisorAdminId",1L);values.put("status","DISABLED");
        values.put("targetGroupId",8L);values.put("expectedVersion",1L);values.put("expectedMemberVersion",0L);
        values.put("expectedQualificationVersion",0L);values.put("expectedAccountVersion",9L);
        values.put("qualificationKind","SERVICE");values.put("state","ENABLED");
        values.put("reason","required reason for parser regression");values.put("sourceGroupVersion",null);
        values.put("targetGroupVersion",1L);values.put("expectedRouteVersion",0L);
        ObjectNode node=json.createObjectNode();
        for(var component:type.getRecordComponents())node.set(component.getName(),json.valueToTree(values.get(component.getName())));
        return node;
    }
    @ParameterizedTest(name="{0}.{1} rejects JSON {2}") @MethodSource("invalidVersions")
    void rejectsNonIntegerAndUnsafeJsonVersions(Class<?> type,String field,String literal) throws Exception {
        ObjectNode node=request(type);node.set(field,json.readTree(literal));
        assertThatThrownBy(()->json.readValue(node.toString(),type)).isInstanceOf(JsonProcessingException.class);
    }
    @ParameterizedTest(name="{0}.{1} preserves safe boundaries") @MethodSource("versionFields")
    void preservesExactSafeIntegerBoundaries(Class<?> type,String field,boolean required) throws Exception {
        for(long value:new long[]{0L,9007199254740991L}) {
            ObjectNode node=request(type);node.put(field,value);Object parsed=json.readValue(node.toString(),type);
            assertThat(type.getMethod(field).invoke(parsed)).isEqualTo(value);
        }
    }
    @ParameterizedTest(name="{0}.{1} preserves existing null contract") @MethodSource("versionFields")
    void preservesNullAndRejectsMissingRequiredVersionsBeforeMutation(Class<?> type,String field,boolean required) throws Exception {
        ObjectNode node=request(type);node.putNull(field);Object parsed=json.readValue(node.toString(),type);
        assertThat(type.getMethod(field).invoke(parsed)).isNull();
        if(!required)return;
        var mapper=mock(SupportGroupMapper.class);var ownership=mock(SupportOwnershipService.class);
        var keys=mock(AdminIdempotencyService.class);var audit=mock(AuditLogService.class);
        var events=mock(ApplicationEventPublisher.class);var service=new SupportGroupService(mapper,ownership,keys,audit,events);
        assertThatThrownBy(()->{
            if(parsed instanceof Rename r)service.rename(8L,"null-version",r);
            else if(parsed instanceof Status r)service.status(8L,"null-version",r);
            else if(parsed instanceof Owner r)service.owner(8L,"null-version",r);
            else if(parsed instanceof Move r)service.move(3L,"null-version",r);
            else if(parsed instanceof Qualification r)service.qualification(3L,"null-version",r);
            else if(parsed instanceof Route r)service.route(99L,"null-version",r);
            else throw new AssertionError("Uncovered request writer");
        }).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(422));
        verifyNoInteractions(mapper,ownership,keys,audit,events);
    }
}

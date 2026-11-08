package com.fast.knowledge.course;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.knowledge.config.KnowledgeProperties;
import com.fast.knowledge.mapper.UserMapper;
import com.fast.knowledge.model.entity.KbUser;
import com.fast.knowledge.security.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseJwtFilterTest {
    @Mock UserMapper users;
    @Mock TokenBlacklistService blacklist;
    @Mock FilterChain chain;
    CourseJwtFilter filter;
    JwtUtil jwt;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    @BeforeEach void setup() {
        var properties=new KnowledgeProperties(); properties.getJwt().setSecret(UUID.randomUUID().toString());
        jwt=new JwtUtil(properties);filter=new CourseJwtFilter(jwt,blacklist,users,new SecurityProblemHandler(new ObjectMapper()));
        request=new MockHttpServletRequest("GET","/api/admin/users");request.setPathInfo("/admin/users");
        request.addHeader("Authorization","Bearer "+jwt.createToken(1L,"fixture_user","ADMIN"));
        response=new MockHttpServletResponse();
    }
    @AfterEach void cleanup() { UserContext.clear();SecurityContextHolder.clearContext(); }
    @Test void databaseReadFailureDoesNotAuthorizeOrLeakDetails() throws Exception {
        when(users.selectById(1L)).thenThrow(new DataAccessResourceFailureException("SQL secret-key absolute-path"));
        filter.doFilter(request,response,chain);
        assertEquals(500,response.getStatus());assertTrue(response.getContentAsString().contains("50001"));
        assertFalse(response.getContentAsString().contains("secret-key"));verifyNoInteractions(chain);
        assertNull(UserContext.get());assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void blacklistReadFailureDoesNotAuthorize() throws Exception {
        when(blacklist.isBlacklisted(anyString())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        filter.doFilter(request,response,chain);assertEquals(500,response.getStatus());
        verifyNoInteractions(users,chain);
    }
    @Test void databaseRoleOverridesSignedRoleAndContextsAreCleaned() throws Exception {
        KbUser user=new KbUser();user.setId(1L);user.setUsername("fixture_user");user.setStatus(1);user.setRole("USER");
        when(users.selectById(1L)).thenReturn(user);
        doAnswer(invocation->{assertEquals("USER",UserContext.get().getRole());
            assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .anyMatch(a->a.getAuthority().equals("ROLE_USER")));return null;}).when(chain).doFilter(request,response);
        filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);
        assertNull(UserContext.get());assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void disabledUserAndSessionVersionMismatchCannotAuthorize() throws Exception {
        KbUser user=new KbUser();user.setId(1L);user.setStatus(0);user.setRole("USER");
        when(users.selectById(1L)).thenReturn(user);
        filter.doFilter(request,response,chain);assertEquals(401,response.getStatus());verifyNoInteractions(chain);
        user.setStatus(1);user.setAuthVersion(1L);response=new MockHttpServletResponse();
        doAnswer(invocation->{assertNull(UserContext.get());return null;}).when(chain).doFilter(request,response);
        filter.doFilter(request,response,chain);assertNull(UserContext.get());
    }
    @Test void revokedTokenDoesNotQueryUserAndClearsPreviousContext() throws Exception {
        when(blacklist.isBlacklisted(anyString())).thenReturn(true);
        UserContext.set(new UserContext());
        doAnswer(invocation->{assertNull(UserContext.get());return null;}).when(chain).doFilter(request,response);
        filter.doFilter(request,response,chain);verifyNoInteractions(users);assertNull(UserContext.get());
    }
}

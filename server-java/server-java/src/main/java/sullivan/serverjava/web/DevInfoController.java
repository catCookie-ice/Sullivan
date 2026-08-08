package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开发调试接口（仅 dev 环境注册，见 {@code app.env=${APP_ENV:dev}}）。
 *
 * <p>生产环境（APP_ENV=prod）不会注册本控制器，/dev/routes 直接 404。</p>
 */
@RestController
@RequestMapping("/dev")
@ConditionalOnProperty(name = "app.env", havingValue = "dev")
@Tag(name = "开发调试", description = "仅开发（dev）环境注册，用于查看路由与排查接口问题；生产环境不存在。")
public class DevInfoController {
    private final RequestMappingHandlerMapping handlerMapping;

    public DevInfoController(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    @GetMapping("/routes")
    @Operation(summary = "列出全部已注册路由（开发调试用）",
            description = "返回所有已注册 HTTP 路由的 HTTP 方法、路径与处理器，按路径排序。"
                    + "仅供开发阶段查看，生产环境该接口不注册。")
    public List<RouteInfo> routes() {
        Map<RequestMappingInfo, HandlerMethod> mappings = handlerMapping.getHandlerMethods();
        return mappings.entrySet().stream()
                .map(entry -> new RouteInfo(
                        methods(entry.getKey()),
                        paths(entry.getKey()),
                        handlerName(entry.getValue())))
                .sorted((a, b) -> a.path().compareTo(b.path()))
                .toList();
    }

    private String methods(RequestMappingInfo info) {
        Set<String> set = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        return set.isEmpty() ? "ANY" : String.join(",", set);
    }

    private String paths(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatterns().stream()
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.joining(","));
        }
        return info.getDirectPaths().stream().sorted().collect(Collectors.joining(","));
    }

    private String handlerName(HandlerMethod method) {
        return method.getBeanType().getSimpleName() + "." + method.getMethod().getName() + "()";
    }

    public record RouteInfo(String method, String path, String handler) {
    }
}

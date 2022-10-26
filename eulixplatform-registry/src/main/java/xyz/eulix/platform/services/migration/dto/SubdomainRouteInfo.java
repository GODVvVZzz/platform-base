package xyz.eulix.platform.services.migration.dto;

import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import javax.validation.constraints.NotBlank;

/**
 * 用户域名映射信息
 */
@Data
public class SubdomainRouteInfo {
    @NotBlank
    @Schema(description = "当前subdomain")
    private String subdomain;

    @NotBlank
    @Schema(description = "重定向subdomain")
    private String subdomainRedirect;
}
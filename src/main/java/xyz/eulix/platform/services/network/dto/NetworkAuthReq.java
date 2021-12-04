package xyz.eulix.platform.services.network.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Data
@AllArgsConstructor(staticName = "of")
public class NetworkAuthReq {
    @Schema(description = "network client id")
    private String clientId;

    @Schema(description = "network client 访问密钥")
    private String secretKey;
}
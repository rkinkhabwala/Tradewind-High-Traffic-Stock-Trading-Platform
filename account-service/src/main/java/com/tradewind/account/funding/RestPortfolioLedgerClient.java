package com.tradewind.account.funding;

import com.tradewind.common.web.RequestIdPropagatingInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;

@Component
class RestPortfolioLedgerClient implements PortfolioLedgerClient {

    private final RestClient client;

    RestPortfolioLedgerClient(RestClient.Builder builder, FundingProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder
                .baseUrl(properties.portfolioUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(new RequestIdPropagatingInterceptor())
                .build();
    }

    @Override
    public LedgerResult deposit(UUID referenceId, UUID accountId, long amountCents) {
        return post("/internal/ledger/deposits", referenceId, accountId, amountCents);
    }

    @Override
    public LedgerResult withdraw(UUID referenceId, UUID accountId, long amountCents) {
        return post("/internal/ledger/withdrawals", referenceId, accountId, amountCents);
    }

    private LedgerResult post(String path, UUID referenceId, UUID accountId, long amountCents) {
        try {
            return client.post().uri(path)
                    .body(Map.of("referenceId", referenceId, "accountId", accountId, "amountCents", amountCents))
                    .retrieve()
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY), (request, response) -> {
                        throw new InsufficientFundsException();
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new PortfolioUnavailableException("portfolio-service returned " + response.getStatusCode(), null);
                    })
                    .body(LedgerResult.class);
        } catch (ResourceAccessException e) {
            throw new PortfolioUnavailableException("portfolio-service unreachable or timed out", e);
        }
    }
}

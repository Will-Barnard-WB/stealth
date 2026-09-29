package com.example.secrets;

/** Fake token for stealth's secrets analyzer tests. Not a real credential. */
public class GitHubClient {

    private static final String TOKEN = "ghp_7ZSXd7lFBilqOuF5j0aiG0IC1DSTRxVsmBCm";

    private static final String TOKEN_ENV_VAR = "GITHUB_TOKEN";

    public String authorizationHeader() {
        return "Bearer " + TOKEN;
    }

    public String tokenEnvVar() {
        return TOKEN_ENV_VAR;
    }
}

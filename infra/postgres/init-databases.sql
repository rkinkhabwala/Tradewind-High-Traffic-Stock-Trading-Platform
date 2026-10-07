-- One logical database per service (spec section 2). Runs once on first container start.
CREATE DATABASE account;
CREATE DATABASE marketdata;
CREATE DATABASE orders;
CREATE DATABASE execution;
CREATE DATABASE portfolio;

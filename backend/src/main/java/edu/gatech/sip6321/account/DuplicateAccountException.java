package edu.gatech.sip6321.account;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("Account already exists");
    }
}

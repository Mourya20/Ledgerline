package com.ledgerline.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerline.domain.AccountEntity;

@Repository
public class AccountRepositoryCustomImpl implements AccountRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public AccountEntity refresh(AccountEntity account) {
        AccountEntity managed = entityManager.find(AccountEntity.class, account.getId());
        if (managed != null) {
            entityManager.refresh(managed);
            return managed;
        }
        return account;
    }
}

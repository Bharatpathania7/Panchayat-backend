package com.panchayat.backend.gifts.service;

import com.panchayat.backend.gifts.entity.GiftDefinition;
import com.panchayat.backend.gifts.repository.GiftDefinitionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps GiftCatalog's prices in step with the gifts table. */
@Service
public class GiftCatalogService {

    private final GiftDefinitionRepository repository;

    public GiftCatalogService(GiftDefinitionRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        Map<String, Long> active = new LinkedHashMap<>();
        for (GiftDefinition g : repository.findByActiveTrueOrderBySortOrderAscPriceAsc()) {
            active.put(g.getId(), g.getPrice());
        }
        GiftCatalog.replace(active);
    }

    /** After the admin change commits (or now, outside a transaction). */
    public void reloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reload();
                }
            });
        } else {
            reload();
        }
    }

    /** Gifts on sale, for the app. */
    public List<GiftDefinition> onSale() {
        return repository.findByActiveTrueOrderBySortOrderAscPriceAsc();
    }
}

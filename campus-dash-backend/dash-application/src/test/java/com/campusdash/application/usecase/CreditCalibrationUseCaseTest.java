package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditScore;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditCalibrationUseCaseTest {

    @Test
    @DisplayName("信用校准用例会规范化窗口天数和批大小")
    void calibrate_normalizes_arguments() {
        FakeCreditRepository repository = new FakeCreditRepository();
        CreditCalibrationUseCase useCase = new CreditCalibrationUseCase(repository);

        int changed = useCase.calibrate(0, 0);

        assertEquals(7, changed);
        assertEquals(1, repository.windowDays);
        assertEquals(1, repository.limit);
    }

    private static final class FakeCreditRepository implements CreditRepository {
        int windowDays;
        int limit;

        @Override
        public int calibrateScores(int windowDays, int limit) {
            this.windowDays = windowDays;
            this.limit = limit;
            return 7;
        }

        @Override public int scoreOf(long userId) { return 60; }
        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
    }
}

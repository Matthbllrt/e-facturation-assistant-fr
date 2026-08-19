"""File de taches locales en arriere-plan.

Les imports d'archives volumineuses (plusieurs gigaoctets) durent plusieurs
minutes : ils sont executes dans un fil d'execution dedie et l'interface
interroge l'avancement, plutot que de bloquer sur une requête HTTP.
"""
from __future__ import annotations

import threading
import traceback
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

from .utils import utcnow_iso


@dataclass
class Job:
    id: str
    label: str
    case_slug: str | None = None
    status: str = "en_attente"      # en_attente | en_cours | termine | erreur
    step: str = ""
    current: int = 0
    total: int = 0
    started_at: str | None = None
    finished_at: str | None = None
    result: Any = None
    error: str | None = None
    logs: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        percent = None
        if self.total:
            percent = min(100, round(100 * self.current / self.total))
        return {
            "id": self.id,
            "label": self.label,
            "case": self.case_slug,
            "status": self.status,
            "step": self.step,
            "current": self.current,
            "total": self.total,
            "percent": percent,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "result": self.result,
            "error": self.error,
            "logs": self.logs[-40:],
        }


class JobManager:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.RLock()

    def create(self, label: str, case_slug: str | None = None) -> Job:
        job = Job(id=uuid.uuid4().hex[:12], label=label, case_slug=case_slug)
        with self._lock:
            self._jobs[job.id] = job
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def list(self, case_slug: str | None = None, limit: int = 50) -> list[dict]:
        with self._lock:
            jobs = list(self._jobs.values())
        if case_slug:
            jobs = [j for j in jobs if j.case_slug == case_slug]
        jobs.sort(key=lambda j: j.started_at or "", reverse=True)
        return [j.to_dict() for j in jobs[:limit]]

    def run(self, job: Job, target: Callable[[Job], Any]) -> Job:
        def wrapper() -> None:
            job.status = "en_cours"
            job.started_at = utcnow_iso()
            try:
                job.result = target(job)
                job.status = "termine"
                job.step = "termine"
            except Exception as exc:
                job.status = "erreur"
                job.error = f"{type(exc).__name__}: {exc}"
                job.logs.append(traceback.format_exc()[-3000:])
            finally:
                job.finished_at = utcnow_iso()

        thread = threading.Thread(target=wrapper, name=f"job-{job.id}", daemon=True)
        thread.start()
        return job

    def progress(self, job: Job) -> Callable[[str, int, int], None]:
        def report(step: str, current: int, total: int) -> None:
            job.step = step
            job.current = current
            job.total = total

        return report

    def purge(self, keep: int = 200) -> int:
        with self._lock:
            if len(self._jobs) <= keep:
                return 0
            finished = sorted(
                (j for j in self._jobs.values() if j.status in {"termine", "erreur"}),
                key=lambda j: j.finished_at or "",
            )
            removed = 0
            for job in finished[: len(self._jobs) - keep]:
                self._jobs.pop(job.id, None)
                removed += 1
            return removed


MANAGER = JobManager()

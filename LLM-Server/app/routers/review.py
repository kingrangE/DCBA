import asyncio
import json
import os
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException
from openai import APIError, APITimeoutError
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, field_validator

from app.routers.exercise import require_api_key
from app.services.exercise_service import exercise_service, unique_object

router = APIRouter(prefix="/review", dependencies=[Depends(require_api_key)])
PROMPT = (Path(__file__).resolve().parents[2] / "instructions/review_feedback_prompt.txt").read_text(encoding="utf-8")
CriterionId = Annotated[str, StringConstraints(pattern=r"^[a-z0-9_]{1,32}$")]
FeedbackText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=240)]


class Criterion(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: CriterionId
    label: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
    description: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=400)]


class ReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question: Annotated[str, StringConstraints(min_length=1)]
    level: Literal["EASY", "MEDIUM", "HARD"]
    modelAnswer: Annotated[str, StringConstraints(min_length=1)]
    criteria: list[Criterion] = Field(min_length=2, max_length=4)
    answer: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]

    @field_validator("criteria")
    @classmethod
    def unique_criteria(cls, criteria):
        if len({criterion.id for criterion in criteria}) != len(criteria):
            raise ValueError("Duplicate criterion")
        return criteria


class CriterionResult(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: CriterionId
    status: Literal["met", "partial", "missing"]
    comment: FeedbackText


class ReviewResult(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    criteria: list[CriterionResult] = Field(min_length=2, max_length=4)
    nextStep: FeedbackText


@router.post("/feedback")
async def feedback(request: ReviewRequest):
    if os.getenv("REVIEW_FEEDBACK_ENABLED", "false").lower() != "true":
        raise HTTPException(status_code=409, detail="Feedback disabled")
    messages = [
        {"role": "system", "content": PROMPT},
        {"role": "user", "content": request.model_dump_json()},
    ]
    try:
        output = await asyncio.wait_for(
            asyncio.to_thread(exercise_service.client.generate, messages, timeout=10.0), timeout=10.0
        )
        result = ReviewResult.model_validate(json.loads(output, object_pairs_hook=unique_object))
        ids = {criterion.id for criterion in request.criteria}
        if len(result.criteria) != len(ids) or {item.id for item in result.criteria} != ids:
            raise ValueError("Feedback criteria mismatch")
        return result
    except (TimeoutError, APITimeoutError):
        raise HTTPException(status_code=504, detail="Feedback provider timed out") from None
    except (APIError, ValueError, RecursionError):
        raise HTTPException(status_code=503, detail="Feedback unavailable") from None

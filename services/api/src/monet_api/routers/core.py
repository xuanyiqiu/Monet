import json
import logging

from fastapi import APIRouter, Depends, Request, Response

from monet_api import __version__
from monet_api.annotator import Annotator, EmptyTextError
from monet_api.auth import current_user, rate_limited_user
from monet_api.errors import Problem
from monet_api.feedback import FeedbackItem
from monet_api.schemas import (
    AnnotateLinesRequest,
    AnnotateLinesResponse,
    AnnotateRequest,
    Annotation,
    FeedbackRequest,
    Health,
    Me,
)

router = APIRouter(prefix="/v1")
feedback_log = logging.getLogger("monet.feedback")


def annotator(request: Request) -> Annotator:
    return request.app.state.annotator


@router.get("/health", response_model=Health, operation_id="health")
async def health(request: Request) -> Health:
    return Health(version=__version__, llm_provider=request.app.state.annotator.llm.name)


@router.get("/me", response_model=Me, operation_id="me")
async def me(user: str = Depends(current_user)) -> Me:
    return Me(user_id=user)


@router.post("/annotate", response_model=Annotation, operation_id="annotate")
async def annotate(
    body: AnnotateRequest, user: str = Depends(rate_limited_user), a: Annotator = Depends(annotator)
) -> Annotation:
    try:
        return await a.annotate(body)
    except EmptyTextError as e:
        raise Problem(422, "Invalid request", str(e)) from e


@router.post("/annotate/lines", response_model=AnnotateLinesResponse, operation_id="annotateLines")
async def annotate_lines(
    body: AnnotateLinesRequest, user: str = Depends(rate_limited_user), a: Annotator = Depends(annotator)
) -> AnnotateLinesResponse:
    return await a.annotate_lines(body)


@router.post("/feedback", status_code=202, operation_id="sendFeedback")
async def feedback(
    body: FeedbackRequest, request: Request, user: str = Depends(rate_limited_user)
) -> Response:
    feedback_log.info(json.dumps({"user": user, **body.model_dump(mode="json")}, ensure_ascii=False))
    await request.app.state.feedback.add(
        FeedbackItem(
            user_id=user,
            text=body.text,
            translation=body.translation,
            target_lang=body.target_lang.value,
            comment=body.comment,
        )
    )
    return Response(status_code=202)
